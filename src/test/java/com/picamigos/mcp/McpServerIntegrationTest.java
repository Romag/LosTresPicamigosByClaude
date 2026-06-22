package com.picamigos.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picamigos.test.FakeAgents;
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
}
