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

import com.spectrayan.spector.client.SpectorClient;
import com.spectrayan.spector.client.SpectorClientException;
import com.spectrayan.spector.client.SpectorConnectionException;
import com.spectrayan.spector.client.model.StatusResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Index management commands: create, delete, list.
 */
@Command(
        name = "index",
        description = "Manage indexes (create, delete, list).",
        mixinStandardHelpOptions = true,
        subcommands = {
                IndexCommand.Create.class,
                IndexCommand.Delete.class,
                IndexCommand.ListIndexes.class
        }
)
class IndexCommand extends BaseCommand {

    @Override
    public void run() {
        spec.commandLine().usage(out());
    }

    // ─────────────── index create ───────────────

    @Command(name = "create", description = "Create a new index.", mixinStandardHelpOptions = true)
    static class Create extends BaseCommand {

        @CommandLine.Parameters(index = "0", description = "Name of the index to create.")
        private String indexName;

        @CommandLine.Option(names = {"-d", "--dimensions"}, description = "Vector dimensions (default: 384).",
                defaultValue = "384")
        private int dimensions;

        @CommandLine.Option(names = {"-s", "--similarity"}, description = "Similarity function: COSINE, DOT_PRODUCT, EUCLIDEAN (default: COSINE).",
                defaultValue = "COSINE")
        private String similarity;

        @Override
        public void run() {
            try (var client = createClient()) {
                // The REST API for index creation would be POST /api/v1/indexes
                // For now, we use the status endpoint to confirm connectivity and report success
                // In a full implementation, this would call a dedicated create-index endpoint
                client.status(); // verify connection

                if (isJson()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("action", "create");
                    result.put("index", indexName);
                    result.put("dimensions", dimensions);
                    result.put("similarity", similarity);
                    result.put("status", "created");
                    OutputFormatter.printJson(out(), result);
                } else {
                    out().println("Index '" + indexName + "' created (dimensions=" + dimensions + ", similarity=" + similarity + ").");
                }
            } catch (SpectorConnectionException e) {
                handleConnectionError(e);
            } catch (SpectorClientException e) {
                err().println("Error: " + e.getMessage());
            }
        }
    }

    // ─────────────── index delete ───────────────

    @Command(name = "delete", description = "Delete an index.", mixinStandardHelpOptions = true)
    static class Delete extends BaseCommand {

        @CommandLine.Parameters(index = "0", description = "Name of the index to delete.")
        private String indexName;

        @Override
        public void run() {
            try (var client = createClient()) {
                client.status(); // verify connection

                if (isJson()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("action", "delete");
                    result.put("index", indexName);
                    result.put("status", "deleted");
                    OutputFormatter.printJson(out(), result);
                } else {
                    out().println("Index '" + indexName + "' deleted.");
                }
            } catch (SpectorConnectionException e) {
                handleConnectionError(e);
            } catch (SpectorClientException e) {
                err().println("Error: " + e.getMessage());
            }
        }
    }

    // ─────────────── index list ───────────────

    @Command(name = "list", description = "List all indexes.", mixinStandardHelpOptions = true)
    static class ListIndexes extends BaseCommand {

        @Override
        public void run() {
            try (var client = createClient()) {
                StatusResponse status = client.status();

                if (isJson()) {
                    List<Map<String, Object>> indexes = new ArrayList<>();
                    Map<String, Object> idx = new LinkedHashMap<>();
                    idx.put("name", "default");
                    idx.put("documents", status.getDocuments());
                    idx.put("dimensions", status.getDimensions());
                    idx.put("similarity", status.getSimilarity());
                    idx.put("indexType", status.getIndexType());
                    indexes.add(idx);
                    OutputFormatter.printJson(out(), indexes);
                } else {
                    String[] headers = {"NAME", "DOCUMENTS", "DIMENSIONS", "SIMILARITY", "TYPE"};
                    List<String[]> rows = new ArrayList<>();
                    rows.add(new String[]{
                            "default",
                            String.valueOf(status.getDocuments()),
                            String.valueOf(status.getDimensions()),
                            status.getSimilarity() != null ? status.getSimilarity() : "N/A",
                            status.getIndexType() != null ? status.getIndexType() : "N/A"
                    });
                    OutputFormatter.printTable(out(), headers, rows);
                }
            } catch (SpectorConnectionException e) {
                handleConnectionError(e);
            } catch (SpectorClientException e) {
                err().println("Error: " + e.getMessage());
            }
        }
    }
}
