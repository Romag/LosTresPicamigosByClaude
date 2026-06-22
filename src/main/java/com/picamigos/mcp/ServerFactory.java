package com.picamigos.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Builds the Picamigos MCP server over a Streamable-HTTP transport hosted on embedded Jetty.
 *
 * <p>The MCP SDK's {@link HttpServletStreamableServerTransportProvider} is itself a servlet, so it is
 * mounted directly into a Jetty servlet context at the MCP endpoint. Binds loopback only.
 */
public final class ServerFactory implements AutoCloseable {

    /** MCP endpoint path; clients connect to {@code http://<host>:<port>/mcp}. */
    public static final String ENDPOINT = "/mcp";

    private final Server jetty;
    private final McpSyncServer mcpServer;

    public ServerFactory(Services services, String host, int port) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(jsonMapper)
                        .mcpEndpoint(ENDPOINT)
                        .build();

        this.mcpServer = McpServer.sync(transport)
                .serverInfo("los-tres-picamigos", "0.1.0")
                .instructions("Delegate work to peer CLI coding agents (codex, claude, antigravity). "
                        + "Use recommend_agent for routing, delegate to run synchronously, and agent_status "
                        + "to check budgets before delegating.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(new PicamigosTools(services, jsonMapper).all())
                .build();

        this.jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(host);
        connector.setPort(port);
        jetty.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), ENDPOINT + "/*");
        jetty.setHandler(context);
    }

    public void start() throws Exception {
        jetty.start();
    }

    /** The actual bound port (useful when constructed with port 0). */
    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    /** Blocks until the server stops. */
    public void join() throws InterruptedException {
        jetty.join();
    }

    @Override
    public void close() {
        try {
            mcpServer.closeGracefully();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            jetty.stop();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
