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
package com.spectrayan.spector.commons.document;

import com.spectrayan.spector.commons.error.SpectorDocumentReadException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Reads Markdown documents preserving heading structure.
 *
 * <p>Heading level indicators (# through ######) are retained as section
 * delimiters in the extracted output. Non-heading markup (bold, italic, links,
 * images, code fences) is stripped to plain text.</p>
 */
public final class MarkdownDocumentReader implements DocumentReader {

    private static final Logger LOG = LoggerFactory.getLogger(MarkdownDocumentReader.class);
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100 MB

    // Patterns for stripping markdown formatting while preserving headings
    private static final Pattern BOLD_ITALIC = Pattern.compile("\\*{1,3}(.+?)\\*{1,3}");
    private static final Pattern UNDERSCORE_EMPHASIS = Pattern.compile("_{1,3}(.+?)_{1,3}");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(.+?)~~");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]+\\)");
    private static final Pattern CODE_FENCE = Pattern.compile("```[^\\n]*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    @Override
    public DocumentResult read(Path file) throws SpectorDocumentReadException {
        String fileName = file.getFileName().toString();

        validateFile(file, fileName);

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String text = extractText(content);

            if (text.isEmpty()) {
                throw new SpectorDocumentReadException(fileName, "Markdown contains no extractable text");
            }

            var metadata = new DocumentMetadata(fileName, "MARKDOWN", text.length());
            return new DocumentResult(text, metadata);

        } catch (SpectorDocumentReadException e) {
            throw e;
        } catch (IOException e) {
            throw new SpectorDocumentReadException(fileName, "unable to read Markdown file", e);
        } catch (Exception e) {
            throw new SpectorDocumentReadException(fileName,
                    "unexpected error reading Markdown: " + e.getMessage(), e);
        }
    }

    @Override
    public String supportedFormat() {
        return "MARKDOWN";
    }

    private void validateFile(Path file, String fileName) {
        if (!Files.exists(file)) {
            throw new SpectorDocumentReadException(fileName, "file does not exist");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                throw new SpectorDocumentReadException(fileName,
                        "file size %d bytes exceeds the 100 MB limit".formatted(size));
            }
        } catch (IOException e) {
            throw new SpectorDocumentReadException(fileName, "unable to determine file size", e);
        }
    }

    private String extractText(String markdown) {
        // Remove code fences first (preserve content as plain text)
        String text = CODE_FENCE.matcher(markdown).replaceAll("$1");

        // Strip inline formatting but keep content
        text = IMAGE.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        text = BOLD_ITALIC.matcher(text).replaceAll("$1");
        text = UNDERSCORE_EMPHASIS.matcher(text).replaceAll("$1");
        text = STRIKETHROUGH.matcher(text).replaceAll("$1");
        text = HTML_TAG.matcher(text).replaceAll("");

        // Process line by line, preserving headings
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n");

        for (String line : lines) {
            String processed = processLine(line);
            if (!processed.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(processed);
            }
        }

        return result.toString();
    }

    private String processLine(String line) {
        String trimmed = line.strip();

        // Preserve heading markers (# through ######)
        if (trimmed.startsWith("#")) {
            return trimmed;
        }

        // Strip list markers
        if (trimmed.matches("^[-*+]\\s+.*")) {
            trimmed = trimmed.replaceFirst("^[-*+]\\s+", "");
        } else if (trimmed.matches("^\\d+\\.\\s+.*")) {
            trimmed = trimmed.replaceFirst("^\\d+\\.\\s+", "");
        }

        // Strip blockquote markers
        if (trimmed.startsWith(">")) {
            trimmed = trimmed.replaceFirst("^>+\\s*", "");
        }

        // Strip horizontal rules
        if (trimmed.matches("^[-*_]{3,}$")) {
            return "";
        }

        return trimmed;
    }
}
