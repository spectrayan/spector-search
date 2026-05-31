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

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SpectorCtl CLI commands.
 * Tests command parsing, --help output, and argument validation
 * without requiring a live server connection.
 */
class SpectorCtlTest {

    private CommandLine createCli() {
        return new CommandLine(new SpectorCtl());
    }

    // ─────────────── Requirement 18.6: --help display ───────────────

    @Test
    void noArgs_printsUsageWithAvailableCommands() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        cli.execute();

        String output = sw.toString();
        assertThat(output).contains("spectorctl");
        assertThat(output).contains("index");
        assertThat(output).contains("ingest");
        assertThat(output).contains("search");
        assertThat(output).contains("status");
    }

    @Test
    void helpFlag_displaysUsage() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("--help");

        assertThat(exitCode).isEqualTo(0);
        String output = sw.toString();
        assertThat(output).contains("Command-line tool for managing Spector");
        assertThat(output).contains("--host");
        assertThat(output).contains("--port");
        assertThat(output).contains("--json");
    }

    @Test
    void versionFlag_displaysVersion() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("--version");

        assertThat(exitCode).isEqualTo(0);
        assertThat(sw.toString()).contains("spectorctl 0.1.0");
    }

    // ─────────────── Requirement 18.5: Invalid arguments ───────────────

    @Test
    void invalidPort_producesError() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));

        int exitCode = cli.execute("--port", "notANumber", "status");

        assertThat(exitCode).isNotEqualTo(0);
        assertThat(errSw.toString()).containsIgnoringCase("invalid");
    }

    @Test
    void searchWithoutQuery_producesError() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));

        int exitCode = cli.execute("search");

        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    void indexCreateWithoutName_producesError() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));

        int exitCode = cli.execute("index", "create");

        assertThat(exitCode).isNotEqualTo(0);
    }

    // ─────────────── Subcommand help ───────────────

    @Test
    void indexHelp_listsSubcommands() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("index", "--help");

        assertThat(exitCode).isEqualTo(0);
        String output = sw.toString();
        assertThat(output).contains("create");
        assertThat(output).contains("delete");
        assertThat(output).contains("list");
    }

    @Test
    void searchHelp_showsOptions() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("search", "--help");

        assertThat(exitCode).isEqualTo(0);
        String output = sw.toString();
        assertThat(output).contains("--top-k");
        assertThat(output).contains("--mode");
    }

    @Test
    void ingestHelp_showsOptions() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("ingest", "--help");

        assertThat(exitCode).isEqualTo(0);
        String output = sw.toString();
        assertThat(output).contains("--id");
        assertThat(output).contains("--content");
        assertThat(output).contains("--file");
    }

    // ─────────────── Requirement 18.2: Configurable host/port ───────────────

    @Test
    void globalOptions_parsedCorrectly() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        // Just verify it parses without error (connection will fail but that's fine)
        // We test that the flags are accepted by picocli
        int exitCode = cli.execute("--host", "myhost", "--port", "9090", "--help");

        assertThat(exitCode).isEqualTo(0);
    }

    // ─────────────── Requirement 18.3: --json flag ───────────────

    @Test
    void jsonFlag_acceptedGlobally() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("--json", "--help");

        assertThat(exitCode).isEqualTo(0);
    }
}
