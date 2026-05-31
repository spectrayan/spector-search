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
import com.spectrayan.spector.client.model.SearchRequest;
import com.spectrayan.spector.client.model.SearchResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search documents in the Spector engine.
 */
@Command(
        name = "search",
        description = "Search for documents in Spector.",
        mixinStandardHelpOptions = true
)
class SearchCommand extends BaseCommand {

    @CommandLine.Parameters(index = "0", description = "Search query text.")
    private String query;

    @CommandLine.Option(names = {"-k", "--top-k"}, description = "Number of results to return (default: 10).",
            defaultValue = "10")
    private int topK;

    @CommandLine.Option(names = {"-m", "--mode"}, description = "Search mode: KEYWORD, VECTOR, HYBRID (default: KEYWORD).",
            defaultValue = "KEYWORD")
    private String mode;

    @Override
    public void run() {
        try (var client = createClient()) {
            SearchRequest request = new SearchRequest();
            request.setText(query);
            request.setMode(mode.toUpperCase());
            request.setTopK(topK);

            SearchResponse response = client.search(request);

            if (isJson()) {
                OutputFormatter.printJson(out(), response);
            } else {
                out().println("Search results (" + response.getTotalHits() + " hits, " + response.getQueryTimeMs() + "ms):");
                out().println();

                if (response.getResults() == null || response.getResults().isEmpty()) {
                    out().println("  No results found.");
                } else {
                    String[] headers = {"#", "ID", "SCORE"};
                    List<String[]> rows = new ArrayList<>();
                    int rank = 1;
                    for (SearchResponse.SearchResult result : response.getResults()) {
                        rows.add(new String[]{
                                String.valueOf(rank++),
                                result.getId(),
                                String.format("%.4f", result.getScore())
                        });
                    }
                    OutputFormatter.printTable(out(), headers, rows);
                }
            }
        } catch (SpectorConnectionException e) {
            handleConnectionError(e);
        } catch (SpectorClientException e) {
            err().println("Error: " + e.getMessage());
        }
    }
}
