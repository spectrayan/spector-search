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
import picocli.CommandLine.Command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Displays the status of the connected Spector instance.
 */
@Command(
        name = "status",
        description = "Show Spector instance status.",
        mixinStandardHelpOptions = true
)
class StatusCommand extends BaseCommand {

    @Override
    public void run() {
        try (var client = createClient()) {
            StatusResponse status = client.status();

            if (isJson()) {
                OutputFormatter.printJson(out(), status);
            } else {
                out().println("Spector Status");
                out().println("=====================");
                String[][] entries = {
                        {"Engine", status.getEngine() != null ? status.getEngine() : "N/A"},
                        {"Version", status.getVersion() != null ? status.getVersion() : "N/A"},
                        {"Documents", String.valueOf(status.getDocuments())},
                        {"Dimensions", String.valueOf(status.getDimensions())},
                        {"Similarity", status.getSimilarity() != null ? status.getSimilarity() : "N/A"},
                        {"Index Type", status.getIndexType() != null ? status.getIndexType() : "N/A"},
                        {"GPU", status.getGpu() != null ? status.getGpu() : "N/A"},
                        {"Reranker", status.getReranker() != null ? status.getReranker() : "N/A"},
                        {"Embedding", status.getEmbedding() != null ? status.getEmbedding() : "N/A"}
                };
                OutputFormatter.printKeyValue(out(), entries);
            }
        } catch (SpectorConnectionException e) {
            handleConnectionError(e);
        } catch (SpectorClientException e) {
            err().println("Error: " + e.getMessage());
        }
    }
}
