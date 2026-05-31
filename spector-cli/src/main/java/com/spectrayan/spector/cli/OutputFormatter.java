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

import java.io.PrintWriter;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Utility for formatting CLI output as either a table or JSON.
 */
final class OutputFormatter {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private OutputFormatter() {}

    /**
     * Prints data as a formatted table with column headers.
     */
    static void printTable(PrintWriter out, String[] headers, List<String[]> rows) {
        if (headers.length == 0) return;

        // Calculate column widths
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < Math.min(row.length, headers.length); i++) {
                widths[i] = Math.max(widths[i], row[i] != null ? row[i].length() : 4);
            }
        }

        // Print header
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append(padRight(headers[i], widths[i]));
        }
        out.println(sb);

        // Print separator
        sb.setLength(0);
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(widths[i]));
        }
        out.println(sb);

        // Print rows
        for (String[] row : rows) {
            sb.setLength(0);
            for (int i = 0; i < headers.length; i++) {
                if (i > 0) sb.append("  ");
                String val = (i < row.length && row[i] != null) ? row[i] : "";
                sb.append(padRight(val, widths[i]));
            }
            out.println(sb);
        }
    }

    /**
     * Prints an object as formatted JSON.
     */
    static void printJson(PrintWriter out, Object value) {
        try {
            out.println(MAPPER.writeValueAsString(value));
        } catch (JacksonException e) {
            out.println("{\"error\": \"Failed to serialize output: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Prints a single key-value pair table (2-column).
     */
    static void printKeyValue(PrintWriter out, String[][] entries) {
        int maxKeyLen = 0;
        for (String[] entry : entries) {
            maxKeyLen = Math.max(maxKeyLen, entry[0].length());
        }
        for (String[] entry : entries) {
            out.printf("%-" + (maxKeyLen + 2) + "s%s%n", entry[0] + ":", entry[1]);
        }
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
