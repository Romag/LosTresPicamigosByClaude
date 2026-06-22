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
import com.picamigos.prompts.PromptTemplate;
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

    /** All tool specifications exposed in this milestone. */
    public List<SyncToolSpecification> all() {
        return List.of(
                listAgents(),
                agentStatus(),
                recommendAgent(),
                delegate(),
                savePrompt(),
                getPrompt(),
                listPrompts(),
                deletePrompt());
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
                        infos.add(new AgentInfo(e.getKey(), c.displayName(), available, c.model(), c.capabilities()));
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

    private SyncToolSpecification delegate() {
        return tool("delegate",
                "Delegate a task to an agent and block until it finishes. Provide either 'agent' or "
                        + "'task_type' (auto-routed), and either 'prompt' or 'prompt_name' (+ 'variables').",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"agent\":{\"type\":\"string\"},"
                        + "\"task_type\":{\"type\":\"string\"},"
                        + "\"prompt\":{\"type\":\"string\"},"
                        + "\"prompt_name\":{\"type\":\"string\"},"
                        + "\"variables\":{\"type\":\"object\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"ask\",\"edit\"]},"
                        + "\"timeout_seconds\":{\"type\":\"integer\"}}}",
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    Optional<String> agent = resolveAgent(args);
                    if (agent.isEmpty()) {
                        return err("Provide 'agent', or a 'task_type' with at least one available agent.");
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
                    Job job = services.executor.startAndWait(agent.get(), prompt, mode, timeout);
                    return json(job.view(VIEW_CHARS));
                });
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

    // ------------------------------------------------------------------ helpers

    private Optional<String> resolveAgent(Map<String, Object> args) {
        String agent = str(args, "agent");
        if (agent != null && !agent.isBlank()) {
            return services.config.find(agent).isPresent() ? Optional.of(agent) : Optional.empty();
        }
        String taskType = str(args, "task_type");
        if (taskType != null && !taskType.isBlank()) {
            return services.router.recommendOne(taskType).map(r -> r.agent());
        }
        return Optional.empty();
    }

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
