package com.spectrayan.spector.commons.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads HTML documents stripping all tags and converting headings/block elements
 * into newline-delimited sections.
 *
 * <p>Uses regex-based parsing to avoid external dependencies. This handles
 * well-formed HTML correctly; deliberately malformed HTML is handled on a
 * best-effort basis.</p>
 */
public final class HtmlDocumentReader implements DocumentReader {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlDocumentReader.class);
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100 MB

    // Block elements that should introduce section breaks (newlines)
    private static final Set<String> BLOCK_ELEMENTS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6",
            "p", "div", "section", "article", "aside", "main", "header", "footer", "nav",
            "blockquote", "pre", "ol", "ul", "li", "table", "tr", "hr", "br",
            "figure", "figcaption", "details", "summary"
    );

    // Patterns
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BLOCK_OPEN_TAG = Pattern.compile(
            "<(h[1-6]|p|div|section|article|aside|main|header|footer|nav|blockquote|pre|ol|ul|li|table|tr|hr|br|figure|figcaption|details|summary)[^>]*/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_CLOSE_TAG = Pattern.compile(
            "</(h[1-6]|p|div|section|article|aside|main|header|footer|nav|blockquote|pre|ol|ul|li|table|tr|figure|figcaption|details|summary)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos|nbsp|#\\d+|#x[0-9a-fA-F]+);");

    @Override
    public DocumentResult read(Path file) throws DocumentReadException {
        String fileName = file.getFileName().toString();

        validateFile(file, fileName);

        try {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            String text = extractText(html);

            if (text.isEmpty()) {
                throw new DocumentReadException(fileName, "HTML contains no extractable text");
            }

            var metadata = new DocumentMetadata(fileName, "HTML", text.length());
            return new DocumentResult(text, metadata);

        } catch (DocumentReadException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentReadException(fileName, "unable to read HTML file", e);
        } catch (Exception e) {
            throw new DocumentReadException(fileName,
                    "unexpected error reading HTML: " + e.getMessage(), e);
        }
    }

    @Override
    public String supportedFormat() {
        return "HTML";
    }

    private void validateFile(Path file, String fileName) {
        if (!Files.exists(file)) {
            throw new DocumentReadException(fileName, "file does not exist");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                throw new DocumentReadException(fileName,
                        "file size %d bytes exceeds the 100 MB limit".formatted(size));
            }
        } catch (IOException e) {
            throw new DocumentReadException(fileName, "unable to determine file size", e);
        }
    }

    private String extractText(String html) {
        // Remove script and style blocks
        String content = SCRIPT_STYLE.matcher(html).replaceAll("");
        // Remove comments
        content = COMMENT.matcher(content).replaceAll("");

        // Replace block-level opening tags with newline markers
        content = BLOCK_OPEN_TAG.matcher(content).replaceAll("\n");
        // Replace block-level closing tags with newline markers
        content = BLOCK_CLOSE_TAG.matcher(content).replaceAll("\n");

        // Strip remaining tags
        content = ALL_TAGS.matcher(content).replaceAll("");

        // Decode HTML entities
        content = decodeEntities(content);

        // Normalize output
        return normalizeOutput(content);
    }

    private String decodeEntities(String text) {
        Matcher m = HTML_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String entity = m.group(1);
            String replacement = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> {
                    if (entity.startsWith("#x")) {
                        yield String.valueOf((char) Integer.parseInt(entity.substring(2), 16));
                    } else if (entity.startsWith("#")) {
                        yield String.valueOf((char) Integer.parseInt(entity.substring(1)));
                    }
                    yield m.group();
                }
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String normalizeOutput(String text) {
        String[] lines = text.split("\\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.replaceAll("\\s+", " ").strip();
            if (!trimmed.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(trimmed);
            }
        }
        return result.toString();
    }
}
