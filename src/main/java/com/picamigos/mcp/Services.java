package com.picamigos.mcp;

import java.nio.file.Path;
import java.time.Clock;

import com.picamigos.board.NoteBoard;
import com.picamigos.config.AgentsConfig;
import com.picamigos.exec.AgentLauncher;
import com.picamigos.jobs.JobExecutor;
import com.picamigos.jobs.JobRegistry;
import com.picamigos.prompts.PromptStore;
import com.picamigos.routing.AgentRouter;
import com.picamigos.usage.UsageLedger;

/**
 * Wires together the collaborators the MCP tools need (config, jobs, usage, routing, prompts) from a
 * single set of runtime parameters. Used by {@link com.picamigos.Main} and by integration tests.
 */
public final class Services implements AutoCloseable {

    /** Default cap on tool-output text length. */
    public static final int DEFAULT_VIEW_CHARS = 200_000;

    private static final int DEFAULT_MAX_CONCURRENT = 6;

    public final AgentsConfig config;
    public final JobRegistry registry;
    public final UsageLedger ledger;
    public final JobExecutor executor;
    public final AgentRouter router;
    public final PromptStore prompts;
    public final NoteBoard board;
    public final boolean skipPermissions;

    public Services(AgentsConfig config, Path repo, Path promptsFile, boolean skipPermissions) {
        this.config = config;
        this.skipPermissions = skipPermissions;
        this.registry = new JobRegistry();
        this.ledger = new UsageLedger(config.windowHours(), Clock.systemUTC());
        this.executor = new JobExecutor(config, new AgentLauncher(repo), registry, ledger, DEFAULT_MAX_CONCURRENT);
        this.router = new AgentRouter(config, ledger);
        this.prompts = new PromptStore(promptsFile);
        this.board = new NoteBoard();
    }

    @Override
    public void close() {
        executor.close();
    }
}
