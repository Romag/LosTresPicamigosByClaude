package com.picamigos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picamigos.test.FakeAgents;
import com.picamigos.util.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test: a real MCP SDK client talks to the live Picamigos server over Streamable HTTP and
 * drives the tools against {@link FakeAgents} (no real CLI / network).
 */
@Timeout(90)
class McpServerIntegrationTest {

    @TempDir
    Path tmp;

    private Services services;
    private ServerFactory server;
    private McpSyncClient client;

    @BeforeEach
    void setUp() throws Exception {
        services = new Services(FakeAgents.config(), Path.of(".").toAbsolutePath(),
                tmp.resolve("prompts.json"), false);
        server = new ServerFactory(services, "127.0.0.1", 0);
        server.start();

        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port())
                        .endpoint(ServerFactory.ENDPOINT)
                        .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                        .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(45)).build();
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
        if (server != null) {
            server.close();
        }
        if (services != null) {
            services.close();
        }
    }

    private static String text(CallToolResult result) {
        return result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .collect(Collectors.joining());
    }

    /** Reads a top-level field from a tool result whose text is a JSON object. */
    private static String field(CallToolResult result, String name) {
        try {
            JsonNode node = Json.MAPPER.readTree(text(result)).get(name);
            return node == null ? null : node.asText();
        } catch (Exception e) {
            throw new RuntimeException("Not JSON: " + text(result), e);
        }
    }

    private String pollJobStatus(String jobId, String target, int maxAttempts) throws InterruptedException {
        String status = "";
        for (int i = 0; i < maxAttempts; i++) {
            status = field(client.callTool(new CallToolRequest("get_job", Map.of("job_id", jobId))), "status");
            if (target.equals(status)) {
                return status;
            }
            Thread.sleep(50);
        }
        return status;
    }

    @Test
    void exposesExpectedTools() {
        Set<String> names = client.listTools().tools().stream()
                .map(Tool::name)
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_agents", "agent_status", "recommend_agent", "delegate",
                "save_prompt", "get_prompt", "list_prompts", "delete_prompt")), names.toString());
    }

    @Test
    void listAgentsReturnsConfiguredAgents() {
        CallToolResult r = client.callTool(new CallToolRequest("list_agents", Map.of()));
        assertFalse(Boolean.TRUE.equals(r.isError()));
        assertTrue(text(r).contains("\"ok\""), text(r));
    }

    @Test
    void delegateRunsAgentAndReturnsOutput() {
        CallToolResult r = client.callTool(new CallToolRequest("delegate",
                Map.of("agent", "ok", "prompt", "hello mcp", "mode", "ask")));
        String out = text(r);
        assertTrue(out.contains("DONE"), out);
        assertTrue(out.contains("PROMPT:hello mcp"), out);
    }

    @Test
    void recommendAgentForReviewReturnsAgents() {
        CallToolResult r = client.callTool(new CallToolRequest("recommend_agent", Map.of("task_type", "review")));
        assertFalse(Boolean.TRUE.equals(r.isError()));
        assertTrue(text(r).contains("\"agent\""), text(r));
    }

    @Test
    void savedPromptRendersAndDelegates() {
        client.callTool(new CallToolRequest("save_prompt",
                Map.of("name", "greet", "template", "Say hi to {who}")));
        CallToolResult got = client.callTool(new CallToolRequest("get_prompt", Map.of("name", "greet")));
        assertTrue(text(got).contains("Say hi to {who}"), text(got));

        CallToolResult delegated = client.callTool(new CallToolRequest("delegate",
                Map.of("agent", "ok", "prompt_name", "greet", "variables", Map.of("who", "mcp"))));
        assertTrue(text(delegated).contains("PROMPT:Say hi to mcp"), text(delegated));
    }

    @Test
    void startJobThenGetAndList() throws Exception {
        CallToolResult started = client.callTool(new CallToolRequest("start_job",
                Map.of("agent", "ok", "prompt", "async hi", "mode", "ask")));
        String jobId = field(started, "id");
        assertNotNull(jobId);

        assertEquals("DONE", pollJobStatus(jobId, "DONE", 100));

        CallToolResult listed = client.callTool(new CallToolRequest("list_jobs", Map.of("agent", "ok")));
        assertTrue(text(listed).contains(jobId), text(listed));
    }

    @Test
    void cancelRunningJob() throws Exception {
        CallToolResult started = client.callTool(new CallToolRequest("start_job",
                Map.of("agent", "veryslow", "prompt", "p", "mode", "ask")));
        String jobId = field(started, "id");
        assertNotNull(jobId);

        Thread.sleep(800); // let the process come up
        CallToolResult cancelled = client.callTool(new CallToolRequest("cancel_job", Map.of("job_id", jobId)));
        assertFalse(Boolean.TRUE.equals(cancelled.isError()), text(cancelled));

        assertEquals("CANCELLED", pollJobStatus(jobId, "CANCELLED", 100));
    }

    @Test
    void noteBoardSharedAcrossCalls() {
        client.callTool(new CallToolRequest("post_note",
                Map.of("thread", "review/1", "author", "codex", "text", "LGTM with nits")));
        client.callTool(new CallToolRequest("post_note",
                Map.of("thread", "review/1", "author", "claude", "text", "Found a bug")));

        CallToolResult notes = client.callTool(new CallToolRequest("get_notes", Map.of("thread", "review/1")));
        String t = text(notes);
        assertTrue(t.contains("LGTM with nits"), t);
        assertTrue(t.contains("Found a bug"), t);
    }
}
