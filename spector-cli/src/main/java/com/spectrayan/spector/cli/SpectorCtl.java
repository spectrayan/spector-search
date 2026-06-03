/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main entry point for the spectorctl command-line tool.
 *
 * <p>Provides subcommands for managing a running Spector instance
 * via its REST API.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * spectorctl [--host HOST] [--port PORT] [--json] COMMAND
 *
 * Commands:
 *   index    Manage indexes (create, delete, list)
 *   ingest   Ingest a document
 *   search   Search for documents
 *   status   Show instance status
 * </pre>
 */
@Command(
        name = "spectorctl",
        description = "Command-line tool for managing Spector instances.",
        mixinStandardHelpOptions = true,
        version = "spectorctl 0.1.0",
        subcommands = {
                IndexCommand.class,
                IngestCommand.class,
                SearchCommand.class,
                StatusCommand.class,
                MemoryCommand.class
        }
)
public class SpectorCtl implements Runnable {

    @Option(names = {"--host"}, description = "Spector host (default: localhost).",
            defaultValue = "localhost", scope = CommandLine.ScopeType.INHERIT)
    String host;

    @Option(names = {"--port"}, description = "Spector port (default: 7070).",
            defaultValue = "7070", scope = CommandLine.ScopeType.INHERIT)
    int port;

    @Option(names = {"--json"}, description = "Output in JSON format.",
            defaultValue = "false", scope = CommandLine.ScopeType.INHERIT)
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // When invoked without a subcommand, print usage (satisfies Req 18.6)
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpectorCtl())
                .setExecutionExceptionHandler(new ExceptionHandler())
                .execute(args);
        System.exit(exitCode);
    }

    /**
     * Handles execution exceptions to provide friendly error messages.
     * Satisfies Req 18.4 (connection errors) and 18.5 (invalid arguments).
     */
    static class ExceptionHandler implements CommandLine.IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine,
                                            CommandLine.ParseResult parseResult) {
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) {
                // NPE and similar exceptions have null messages — print the class + stack trace
                commandLine.getErr().println("Error: " + ex.getClass().getSimpleName());
                ex.printStackTrace(commandLine.getErr());
            } else {
                commandLine.getErr().println("Error: " + msg);
            }
            return 1;
        }
    }
}
