package com.spectrayan.spector.commons;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContentExtractor}.
 */
class ContentExtractorTest {

    // ─────────────── XML ───────────────

    @Test
    void extractFromSimpleXml() {
        String xml = "<doc><title>Java Search</title><body>SIMD vector engine</body></doc>";
        String text = ContentExtractor.fromXml(xml);
        assertThat(text).contains("Java Search", "SIMD vector engine");
        assertThat(text).doesNotContain("<", ">");
    }

    @Test
    void extractFromXmlWithAttributes() {
        String xml = "<item id=\"1\" type=\"book\"><name>Effective Java</name></item>";
        String text = ContentExtractor.fromXml(xml);
        assertThat(text).contains("Effective Java");
        assertThat(text).doesNotContain("id=", "type=");
    }

    @Test
    void extractFromXmlWithCdata() {
        String xml = "<data><![CDATA[Special content & more]]></data>";
        String text = ContentExtractor.fromXml(xml);
        assertThat(text).contains("Special content & more");
    }

    @Test
    void extractFromXmlWithEntities() {
        String xml = "<text>foo &amp; bar &lt; baz</text>";
        String text = ContentExtractor.fromXml(xml);
        assertThat(text).contains("foo & bar < baz");
    }

    @Test
    void extractFromEmptyXml() {
        assertThat(ContentExtractor.fromXml("")).isEmpty();
        assertThat(ContentExtractor.fromXml(null)).isEmpty();
    }

    // ─────────────── JSON ───────────────

    @Test
    void extractFromSimpleJson() {
        String json = """
                {"title": "Vector Search", "author": "Spectrayan", "year": 2026}
                """;
        String text = ContentExtractor.fromJson(json);
        assertThat(text).contains("Vector Search", "Spectrayan");
    }

    @Test
    void extractFromNestedJson() {
        String json = """
                {"doc": {"title": "HNSW Index", "tags": ["search", "vector", "simd"]}}
                """;
        String text = ContentExtractor.fromJson(json);
        assertThat(text).contains("HNSW Index", "search", "vector", "simd");
    }

    @Test
    void extractFromJsonAll() {
        String json = """
                {"name": "test", "value": "hello"}
                """;
        String text = ContentExtractor.fromJsonAll(json);
        assertThat(text).contains("name", "test", "value", "hello");
    }

    @Test
    void extractFromEmptyJson() {
        assertThat(ContentExtractor.fromJson("")).isEmpty();
        assertThat(ContentExtractor.fromJson(null)).isEmpty();
    }

    // ─────────────── Java Objects ───────────────

    @Test
    void extractFromJavaToString() {
        String obj = "Document{id=doc-1, title=Hello World, content=Search engine test, score=0.95}";
        String text = ContentExtractor.fromJavaObject(obj);
        assertThat(text).contains("Hello World", "Search engine test");
        assertThat(text).doesNotContain("0.95");
    }

    @Test
    void extractFromJavaRecordToString() {
        String obj = "ScoredResult[id=doc-42, index=42, score=0.87]";
        String text = ContentExtractor.fromJavaObject(obj);
        assertThat(text).contains("doc-42");
    }

    @Test
    void extractFromEmptyJavaObject() {
        assertThat(ContentExtractor.fromJavaObject("")).isEmpty();
        assertThat(ContentExtractor.fromJavaObject(null)).isEmpty();
    }

    // ─────────────── Auto-detect ───────────────

    @Test
    void autoDetectsXml() {
        String xml = "<root><item>test data</item></root>";
        String text = ContentExtractor.extract(xml);
        assertThat(text).contains("test data");
    }

    @Test
    void autoDetectsJson() {
        String json = "{\"key\": \"value\"}";
        String text = ContentExtractor.extract(json);
        assertThat(text).contains("value");
    }

    @Test
    void autoDetectsJavaObject() {
        String obj = "MyClass{name=hello, active=true}";
        String text = ContentExtractor.extract(obj);
        assertThat(text).contains("hello");
    }

    @Test
    void plainTextPassesThrough() {
        String text = "just plain text for indexing";
        assertThat(ContentExtractor.extract(text)).isEqualTo(text);
    }
}
