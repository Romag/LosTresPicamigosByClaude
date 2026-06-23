package com.picamigos.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BiFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.picamigos.config.AgentConfig;
import com.picamigos.exec.ExecutableResolver;
import com.picamigos.jobs.Job;
import com.picamigos.jobs.JobStatus;
import com.picamigos.prompts.PromptTemplate;
import com.picamigos.routing.AgentRecommendation;
import com.picamigos.util.Json;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Defines the Picamigos MCP tools and binds them to the underlying {@link Services}.
 *
 * <p>This milestone (M9) exposes: {@code list_agents}, {@code agent_status}, {@code recommend_agent},
 * {@code delegate}, and the prompt-library tools. Async job tools and the note board are added later.
 */
public final class PicamigosTools {

    private static final int VIEW_CHARS = Services.DEFAULT_VIEW_CHARS;

    private final Services services;
    private final McpJsonMapper jsonMapper;

    public PicamigosTools(Services services, McpJsonMapper jsonMapper) {
        this.services = services;
        this.jsonMapper = jsonMapper;
    }

    /** All tool specifications exposed by the server. */
    public List<SyncToolSpecification> all() {
        return List.of(
                listAgents(),
                agentStatus(),
                recommendAgent(),
                delegate(),
                startJob(),
                getJob(),
                listJobs(),
                cancelJob(),
                savePrompt(),
                getPrompt(),
                listPrompts(),
                deletePrompt(),
                postNote(),
                getNotes());
    }

    // ------------------------------------------------------------------ tools

    private SyncToolSpecification listAgents() {
        return tool("list_agents",
                "List configured agents with availability, model, and capability scores.",
                "{\"type\":\"object\",\"properties\":{}}",
                (exchange, request) -> {
                    List<AgentInfo> infos = new ArrayList<>();
                    for (Map.Entry<String, AgentConfig> e : services.config.agents().entrySet()) {
                        AgentConfig c = e.getValue();
                        boolean available = ExecutableResolver.resolve(c.executable()).isPresent();
                        infos.add(new AgentInfo(e.getKey(), c.displayName(), available, c.enabled(),
                                c.model(), c.capabilities()));
                    }
                    return json(infos);
                });
    }

    private SyncToolSpecification agentStatus() {
        return tool("agent_status",
                "Report each agent's budget/availability within its rolling usage window (or one agent).",
                "{\"type\":\"object\",\"properties\":{\"agent\":{\"type\":\"string\","
                        + "\"description\":\"optional agent name/alias; omit for all\"}}}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String agent = str(args, "agent");
                    if (agent != null && !agent.isBlank()) {
                        Optional<String> canonical = services.config.resolveName(agent);
                        if (canonical.isEmpty()) {
                            return err("Unknown agent: " + agent);
                        }
                        return json(services.ledger.status(canonical.get()));
                    }
                    return json(services.config.agentNames().stream()
                            .map(services.ledger::status)
                            .toList());
                });
    }

    private SyncToolSpecification recommendAgent() {
        return tool("recommend_agent",
                "Recommend the best available agent(s) for a task type (implement, reason, plan, review, "
                        + "test, docs). For 'review', returns all available agents.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"task_type\":{\"type\":\"string\"},"
                        + "\"count\":{\"type\":\"integer\",\"description\":\"max picks (ignored for review)\"}},"
                        + "\"required\":[\"task_type\"]}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String taskType = str(args, "task_type");
                    if (taskType == null || taskType.isBlank()) {
                        return err("'task_type' is required");
                    }
                    Integer count = intOrNull(args, "count");
                    return json(services.router.recommend(taskType, count == null ? 1 : count));
                });
    }

    /** Shared input schema for delegate / start_job. */
    private static final String DELEGATION_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"agent\":{\"type\":\"string\"},"
            + "\"task_type\":{\"type\":\"string\"},"
            + "\"prompt\":{\"type\":\"string\"},"
            + "\"prompt_name\":{\"type\":\"string\"},"
            + "\"variables\":{\"type\":\"object\"},"
            + "\"mode\":{\"type\":\"string\",\"enum\":[\"ask\",\"edit\"]},"
            + "\"timeout_seconds\":{\"type\":\"integer\"}}}";

    private static final String JOB_ID_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"job_id\":{\"type\":\"string\"}},\"required\":[\"job_id\"]}";

    private SyncToolSpecification delegate() {
        return tool("delegate",
                "Delegate a task to an agent and block until it finishes. Provide either 'agent' or "
                        + "'task_type' (auto-routed), and either 'prompt' or 'prompt_name' (+ 'variables').",
                DELEGATION_SCHEMA,
                (exchange, request) -> runDelegation(request.arguments(), true));
    }

    private SyncToolSpecification startJob() {
        return tool("start_job",
                "Start a delegation asynchronously and return a job id immediately. Poll with get_job. "
                        + "Call repeatedly to fan out work (e.g. parallel reviews) to multiple agents.",
                DELEGATION_SCHEMA,
                (exchange, request) -> runDelegation(request.arguments(), false));
    }

    private SyncToolSpecification getJob() {
        return tool("get_job",
                "Get a job's status and output (partial while running) by id.",
                JOB_ID_SCHEMA,
                (exchange, request) -> {
                    String id = str(request.arguments(), "job_id");
                    if (id == null || id.isBlank()) {
                        return err("'job_id' is required");
                    }
                    return services.registry.get(id)
                            .map(j -> json(j.view(VIEW_CHARS)))
                            .orElseGet(() -> err("No job: " + id));
                });
    }

    private SyncToolSpecification listJobs() {
        return tool("list_jobs",
                "List jobs (newest first), optionally filtered by agent and/or status.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"agent\":{\"type\":\"string\"},"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"running\",\"done\",\"failed\","
                        + "\"timeout\",\"rate_limited\",\"cancelled\"]}}}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String agent = str(args, "agent");
                    String agentFilter = (agent == null || agent.isBlank())
                            ? null : services.config.resolveName(agent).orElse(agent);
                    String statusStr = str(args, "status");
                    JobStatus statusFilter = null;
                    if (statusStr != null && !statusStr.isBlank()) {
                        try {
                            statusFilter = JobStatus.valueOf(statusStr.trim().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            return err("Invalid status: " + statusStr);
                        }
                    }
                    return json(services.registry.list(agentFilter, statusFilter, VIEW_CHARS));
                });
    }

    private SyncToolSpecification cancelJob() {
        return tool("cancel_job",
                "Cancel a running job by id (force-kills the agent process).",
                JOB_ID_SCHEMA,
                (exchange, request) -> {
                    String id = str(request.arguments(), "job_id");
                    if (id == null || id.isBlank()) {
                        return err("'job_id' is required");
                    }
                    return services.executor.cancel(id)
                            ? ok("Cancelling job: " + id)
                            : err("Job not found or already finished: " + id);
                });
    }

    private CallToolResult runDelegation(Map<String, Object> args, boolean wait) {
        String agentArg = str(args, "agent");
        String taskType = str(args, "task_type");
        String agent;
        if (agentArg != null && !agentArg.isBlank()) {
            Optional<String> canonical = services.config.resolveName(agentArg);
            if (canonical.isEmpty()) {
                return err("Unknown agent: " + agentArg);
            }
            agent = canonical.get(); // canonicalize so jobs/usage key off the canonical name
        } else if (taskType != null && !taskType.isBlank()) {
            Optional<AgentRecommendation> rec = services.router.recommendOne(taskType);
            if (rec.isEmpty()) {
                return err("No agent available for task_type '" + taskType
                        + "' (candidates may be disabled, rate-limited, or not installed). "
                        + "Check agent_status, or pass an explicit 'agent'.");
            }
            agent = rec.get().agent();
        } else {
            return err("Provide 'agent', or 'task_type' for auto-routing.");
        }

        String prompt;
        try {
            prompt = resolvePrompt(args);
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return err(e.getMessage());
        }
        String mode = str(args, "mode");
        if (mode == null || mode.isBlank()) {
            mode = services.skipPermissions ? "edit" : "ask";
        }
        Integer timeout = intOrNull(args, "timeout_seconds");
        Job job = wait
                ? services.executor.startAndWait(agent, prompt, mode, timeout)
                : services.executor.start(agent, prompt, mode, timeout);
        return json(job.view(VIEW_CHARS));
    }

    private SyncToolSpecification savePrompt() {
        return tool("save_prompt",
                "Save or update a reusable prompt template (use {var} placeholders).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\"},"
                        + "\"template\":{\"type\":\"string\"},"
                        + "\"description\":{\"type\":\"string\"},"
                        + "\"default_task_type\":{\"type\":\"string\"}},"
                        + "\"required\":[\"name\",\"template\"]}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    try {
                        PromptTemplate t = new PromptTemplate(
                                str(args, "name"), str(args, "template"),
                                str(args, "description"), str(args, "default_task_type"));
                        services.prompts.save(t);
                        return ok("Saved prompt: " + t.name());
                    } catch (IllegalArgumentException e) {
                        return err(e.getMessage());
                    }
                });
    }

    private SyncToolSpecification getPrompt() {
        return tool("get_prompt",
                "Get a saved prompt template by name.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                (exchange, request) -> {
                    String name = str(request.arguments(), "name");
                    return services.prompts.get(name)
                            .map(this::json)
                            .orElseGet(() -> err("No prompt named: " + name));
                });
    }

    private SyncToolSpecification listPrompts() {
        return tool("list_prompts",
                "List all saved prompt templates.",
                "{\"type\":\"object\",\"properties\":{}}",
                (exchange, request) -> json(services.prompts.list()));
    }

    private SyncToolSpecification deletePrompt() {
        return tool("delete_prompt",
                "Delete a saved prompt template by name.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                (exchange, request) -> {
                    String name = str(request.arguments(), "name");
                    return services.prompts.delete(name)
                            ? ok("Deleted prompt: " + name)
                            : err("No prompt named: " + name);
                });
    }

    private SyncToolSpecification postNote() {
        return tool("post_note",
                "Post a comment to a shared thread visible to all agents (e.g. review feedback).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"thread\":{\"type\":\"string\"},"
                        + "\"author\":{\"type\":\"string\"},"
                        + "\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"thread\",\"author\",\"text\"]}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String thread = str(args, "thread");
                    if (thread == null || thread.isBlank()) {
                        return err("'thread' is required");
                    }
                    return json(services.board.post(thread, str(args, "author"), str(args, "text")));
                });
    }

    private SyncToolSpecification getNotes() {
        return tool("get_notes",
                "Get all comments in a shared thread, in posting order.",
                "{\"type\":\"object\",\"properties\":{\"thread\":{\"type\":\"string\"}},"
                        + "\"required\":[\"thread\"]}",
                (exchange, request) -> {
                    String thread = str(request.arguments(), "thread");
                    if (thread == null || thread.isBlank()) {
                        return err("'thread' is required");
                    }
                    return json(services.board.get(thread));
                });
    }

    // ------------------------------------------------------------------ helpers

    private String resolvePrompt(Map<String, Object> args) {
        String prompt = str(args, "prompt");
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        String promptName = str(args, "prompt_name");
        if (promptName != null && !promptName.isBlank()) {
            return services.prompts.render(promptName, strMap(args, "variables"));
        }
        throw new IllegalArgumentException("Provide 'prompt' or 'prompt_name'.");
    }

    private SyncToolSpecification tool(String name, String description, String inputSchemaJson,
                                       BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(jsonMapper, inputSchemaJson)
                .build();
        return new SyncToolSpecification(tool, handler);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> strMap(Map<String, Object> args, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args.get(key) instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder().addTextContent(text).build();
    }

    private static CallToolResult err(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private CallToolResult json(Object value) {
        try {
            return ok(Json.MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return err("Serialization error: " + e.getMessage());
        }
    }
}
