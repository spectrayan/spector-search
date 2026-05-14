package com.spectrayan.spector.commons;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts searchable text from structured content (XML, JSON, Java object toString).
 *
 * <p>Strips structural tokens (braces, brackets, tags, colons) and extracts
 * only the human-readable text values for indexing.</p>
 */
public final class ContentExtractor {

    private ContentExtractor() {}

    // ─────────────── XML ───────────────

    private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern XML_CDATA = Pattern.compile("<!\\[CDATA\\[(.+?)]]>", Pattern.DOTALL);
    private static final Pattern XML_ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos);");

    /**
     * Extracts text content from XML, stripping all tags.
     *
     * @param xml the XML string
     * @return extracted text
     */
    public static String fromXml(String xml) {
        if (xml == null || xml.isEmpty()) return "";

        // Extract CDATA sections first
        String result = XML_CDATA.matcher(xml).replaceAll("$1");
        // Strip tags
        result = XML_TAG.matcher(result).replaceAll(" ");
        // Decode basic entities
        result = XML_ENTITY.matcher(result).replaceAll(m -> switch (m.group(1)) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> m.group();
        });
        return normalizeWhitespace(result);
    }

    // ─────────────── JSON ───────────────

    private static final Pattern JSON_STRING_VALUE = Pattern.compile("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");

    /**
     * Extracts all string values from JSON, ignoring keys and structural tokens.
     *
     * @param json the JSON string
     * @return extracted text from all string values
     */
    public static String fromJson(String json) {
        if (json == null || json.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        Matcher m = JSON_STRING_VALUE.matcher(json);

        int lastEnd = 0;
        while (m.find()) {
            // Check if this string is a key (followed by ':') or a value
            String between = json.substring(lastEnd, m.start()).trim();
            lastEnd = m.end();

            // After a colon, we have a value; after comma/open bracket, we have a key
            if (between.endsWith(":")) {
                sb.append(m.group(1)).append(' ');
            } else if (between.isEmpty() || between.endsWith(",") || between.endsWith("[")
                    || between.endsWith("{")) {
                String after = json.substring(m.end()).stripLeading();
                if (!after.startsWith(":")) {
                    sb.append(m.group(1)).append(' ');
                }
            }
        }

        return normalizeWhitespace(sb.toString());
    }

    /**
     * Extracts ALL string values from JSON (both keys and values).
     * Useful when field names themselves are meaningful (e.g., dynamic schemas).
     *
     * @param json the JSON string
     * @return extracted text from all strings
     */
    public static String fromJsonAll(String json) {
        if (json == null || json.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        Matcher m = JSON_STRING_VALUE.matcher(json);
        while (m.find()) {
            String value = m.group(1);
            if (!value.isEmpty()) {
                sb.append(value).append(' ');
            }
        }
        return normalizeWhitespace(sb.toString());
    }

    // ─────────────── Java Objects ───────────────

    private static final Pattern JAVA_CLASS = Pattern.compile("\\w+\\{");
    private static final Pattern JAVA_FIELD = Pattern.compile("(\\w+)=([^,}]+)");

    /**
     * Extracts field values from a Java toString() output.
     * Handles formats like: {@code ClassName{field1=value1, field2=value2}}
     *
     * @param toStringOutput the toString() representation
     * @return extracted field values as text
     */
    public static String fromJavaObject(String toStringOutput) {
        if (toStringOutput == null || toStringOutput.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        Matcher m = JAVA_FIELD.matcher(toStringOutput);
        while (m.find()) {
            String value = m.group(2).trim();
            if (!value.matches("^-?\\d+\\.?\\d*$")
                    && !value.equals("true") && !value.equals("false")
                    && !value.equals("null")) {
                sb.append(value).append(' ');
            }
        }
        return normalizeWhitespace(sb.toString());
    }

    /**
     * Auto-detects content type and extracts text.
     *
     * @param content the raw content (XML, JSON, or plain text)
     * @return extracted text
     */
    public static String extract(String content) {
        if (content == null || content.isEmpty()) return "";
        String trimmed = content.trim();

        if (trimmed.startsWith("<")) return fromXml(trimmed);
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return fromJson(trimmed);
        if (trimmed.contains("{") && trimmed.contains("=")) return fromJavaObject(trimmed);

        return content; // plain text
    }

    static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
