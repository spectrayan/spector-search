package com.spectrayan.spector.storage;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a document with its text content and metadata.
 *
 * <p>Used by the indexing pipeline to associate searchable text and
 * arbitrary metadata with a unique identifier. The vector embedding
 * is stored separately in a {@link VectorStore}.</p>
 *
 * @param id       unique document identifier
 * @param title    document title (may be empty)
 * @param content  full text content for keyword indexing
 * @param metadata arbitrary key-value metadata
 */
public record Document(
        String id,
        String title,
        String content,
        Map<String, Object> metadata
) {
    public Document {
        if (id == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "id"); }
        if (content == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "content"); }
        if (title == null) title = "";
        if (metadata == null) metadata = Map.of();
    }

    /**
     * Convenience factory for creating a document with just ID and content.
     *
     * @param id      document ID
     * @param content text content
     * @return new Document
     */
    public static Document of(String id, String content) {
        return new Document(id, "", content, Map.of());
    }

    /**
     * Convenience factory with title.
     *
     * @param id      document ID
     * @param title   document title
     * @param content text content
     * @return new Document
     */
    public static Document of(String id, String title, String content) {
        return new Document(id, title, content, Map.of());
    }
}
