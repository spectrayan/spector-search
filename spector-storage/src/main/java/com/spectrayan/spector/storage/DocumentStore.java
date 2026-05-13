package com.spectrayan.spector.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory document metadata store.
 *
 * <p>Provides a simple ID-keyed store for {@link Document} objects.
 * Designed for concurrent access from virtual threads.</p>
 */
public class DocumentStore implements AutoCloseable {

    private final Map<String, Document> documents;

    public DocumentStore() {
        this.documents = new ConcurrentHashMap<>();
    }

    public DocumentStore(int initialCapacity) {
        this.documents = new ConcurrentHashMap<>(initialCapacity);
    }

    /**
     * Stores a document, replacing any existing entry with the same ID.
     *
     * @param document the document to store
     */
    public void put(Document document) {
        documents.put(document.id(), document);
    }

    /**
     * Retrieves a document by ID.
     *
     * @param id the document identifier
     * @return the document, or {@code null} if not found
     */
    public Document get(String id) {
        return documents.get(id);
    }

    /**
     * Checks whether a document with the given ID exists.
     *
     * @param id the document identifier
     * @return true if present
     */
    public boolean contains(String id) {
        return documents.containsKey(id);
    }

    /**
     * Removes a document by ID.
     *
     * @param id the document identifier
     * @return the removed document, or {@code null} if not found
     */
    public Document remove(String id) {
        return documents.remove(id);
    }

    /**
     * Returns the number of stored documents.
     *
     * @return document count
     */
    public int size() {
        return documents.size();
    }

    /**
     * Returns an unmodifiable view of all documents.
     *
     * @return all stored documents
     */
    public Map<String, Document> all() {
        return Map.copyOf(documents);
    }

    @Override
    public void close() {
        documents.clear();
    }
}
