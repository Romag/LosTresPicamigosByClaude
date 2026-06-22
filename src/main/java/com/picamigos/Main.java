package com.picamigos;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.picamigos.config.AgentsConfig;
import com.picamigos.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Los Tres Picamigos MCP server.
 *
 * <p>For now (M1) this only parses command-line options and logs them; the HTTP/MCP server is wired
 * up in a later milestone. All logging goes to stderr (see {@code simplelogger.properties}) so that
 * stdout stays clean.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(ServerOptions.usage());
            System.exit(2);
            return;
        }

        log.info("Los Tres Picamigos MCP server starting with options:");
        log.info("  host         = {}", options.host());
        log.info("  port         = {}", options.port());
        log.info("  repo         = {}", options.repo());
        log.info("  config       = {}", options.config() == null ? "(bundled defaults)" : options.config());
        log.info("  prompts-file = {}", options.promptsFile());
        log.info("  window-hours = {}", options.windowHours());

        AgentsConfig agents = ConfigLoader.load(options.config());
        log.info("Loaded {} agents: {}", agents.agentNames().size(), agents.agentNames());

        // TODO(M9): build and start the MCP HTTP server here.
        log.info("Scaffold ready: HTTP/MCP server not yet wired, exiting.");
    }

    /** Parsed command-line options for the server. */
    public record ServerOptions(
            String host,
            int port,
            Path repo,
            Path config,
            Path promptsFile,
            int windowHours) {

        static final String DEFAULT_HOST = "127.0.0.1";
        static final int DEFAULT_PORT = 8765;
        static final int DEFAULT_WINDOW_HOURS = 5;

        static ServerOptions parse(String[] args) {
            String host = DEFAULT_HOST;
            int port = DEFAULT_PORT;
            Path repo = Paths.get("").toAbsolutePath();
            Path config = null;
            Path promptsFile = null;
            int windowHours = DEFAULT_WINDOW_HOURS;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--host" -> host = requireValue(args, ++i, "--host");
                    case "--port" -> port = parseIntValue(requireValue(args, ++i, "--port"), "--port");
                    case "--repo" -> repo = Paths.get(requireValue(args, ++i, "--repo")).toAbsolutePath();
                    case "--config" -> config = Paths.get(requireValue(args, ++i, "--config")).toAbsolutePath();
                    case "--prompts-file" ->
                            promptsFile = Paths.get(requireValue(args, ++i, "--prompts-file")).toAbsolutePath();
                    case "--window-hours" ->
                            windowHours = parseIntValue(requireValue(args, ++i, "--window-hours"), "--window-hours");
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (promptsFile == null) {
                promptsFile = repo.resolve(".picamigos").resolve("prompts.json");
            }
            return new ServerOptions(host, port, repo, config, promptsFile, windowHours);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        private static int parseIntValue(String value, String flag) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value for " + flag + " must be an integer, got: " + value);
            }
        }

        static String usage() {
            return """
                   Usage: java -jar picamigos-mcp.jar [options]
                     --host <addr>          bind address (default 127.0.0.1, loopback only)
                     --port <n>             bind port (default 8765)
                     --repo <dir>           working directory delegated agents run in (default: current dir)
                     --config <file>        agent config override (default: bundled agents.default.json)
                     --prompts-file <file>  prompt library JSON (default: <repo>/.picamigos/prompts.json)
                     --window-hours <n>     usage window length in hours (default 5)
                   """;
        }
    }
}
