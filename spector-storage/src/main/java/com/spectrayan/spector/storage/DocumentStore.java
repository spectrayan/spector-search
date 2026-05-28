package com.spectrayan.spector.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory document metadata store with optional disk persistence.
 *
 * <p>Provides a simple ID-keyed store for {@link Document} objects.
 * Designed for concurrent access from virtual threads.</p>
 *
 * <h3>Persistence</h3>
 * <p>Supports binary serialization via {@link #save(Path)} and {@link #load(Path)}.
 * Uses a "DOCS" magic header followed by variable-length records.</p>
 */
public class DocumentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocumentStore.class);

    /** File magic: "DOCS" in ASCII. */
    private static final int DOCS_MAGIC = 0x444F4353;

    /** File format version. */
    private static final int DOCS_VERSION = 1;

    /** File header: 4B magic + 4B version + 4B count + 4B reserved = 16 bytes. */
    private static final int FILE_HEADER_BYTES = 16;

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

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves all documents to a binary file.
     *
     * @param filePath path to write
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create document store directory", e);
            }
        }

        try (FileChannel ch = FileChannel.open(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            header.putInt(DOCS_MAGIC);
            header.putInt(DOCS_VERSION);
            header.putInt(documents.size());
            header.putInt(0);
            header.flip();
            ch.write(header);

            for (Document doc : documents.values()) {
                writeDocument(ch, doc);
            }

            ch.force(true);
            log.info("DocumentStore saved: {} documents → {}", documents.size(), filePath);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save DocumentStore: " + filePath, e);
        }
    }

    /**
     * Loads documents from a binary file, or returns a new empty store.
     *
     * @param filePath path to read
     * @return populated DocumentStore (or empty if file missing)
     */
    public static DocumentStore load(Path filePath) {
        DocumentStore store = new DocumentStore();

        if (filePath == null || !Files.exists(filePath)) {
            log.info("DocumentStore file not found, starting fresh: {}", filePath);
            return store;
        }

        try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.READ)) {
            if (ch.size() < FILE_HEADER_BYTES) {
                log.warn("DocumentStore file too small, starting fresh");
                return store;
            }

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int count = header.getInt();
            header.getInt();

            if (magic != DOCS_MAGIC || version != DOCS_VERSION) {
                log.warn("Invalid DocumentStore file header, starting fresh");
                return store;
            }

            for (int i = 0; i < count; i++) {
                Document doc = readDocument(ch);
                store.put(doc);
            }

            log.info("DocumentStore loaded: {} documents from {}", store.size(), filePath);

        } catch (IOException e) {
            log.error("Failed to load DocumentStore, starting fresh: {}", e.getMessage());
        }

        return store;
    }

    private static void writeDocument(FileChannel ch, Document doc) throws IOException {
        byte[] idBytes = doc.id().getBytes(StandardCharsets.UTF_8);
        byte[] titleBytes = doc.title().getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = doc.content().getBytes(StandardCharsets.UTF_8);

        int size = 4 + idBytes.length + 4 + titleBytes.length + 4 + contentBytes.length + 4;
        for (var entry : doc.metadata().entrySet()) {
            size += 4 + entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + 4 + String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8).length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        writeStringToBuf(buf, idBytes);
        writeStringToBuf(buf, titleBytes);
        writeStringToBuf(buf, contentBytes);
        buf.putInt(doc.metadata().size());
        for (var entry : doc.metadata().entrySet()) {
            writeStringToBuf(buf, entry.getKey().getBytes(StandardCharsets.UTF_8));
            writeStringToBuf(buf, String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        buf.flip();
        ch.write(buf);
    }

    private static Document readDocument(FileChannel ch) throws IOException {
        String id = readString(ch);
        String title = readString(ch);
        String content = readString(ch);

        ByteBuffer countBuf = ByteBuffer.allocate(4);
        ch.read(countBuf);
        countBuf.flip();
        int metaCount = countBuf.getInt();

        Map<String, Object> metadata = new HashMap<>();
        for (int i = 0; i < metaCount; i++) {
            String key = readString(ch);
            String value = readString(ch);
            metadata.put(key, value);
        }

        return new Document(id, title, content, metadata);
    }

    private static void writeStringToBuf(ByteBuffer buf, byte[] bytes) {
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    private static String readString(FileChannel ch) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        ch.read(lenBuf);
        lenBuf.flip();
        int len = lenBuf.getInt();
        if (len == 0) return "";
        ByteBuffer strBuf = ByteBuffer.allocate(len);
        ch.read(strBuf);
        strBuf.flip();
        return new String(strBuf.array(), 0, len, StandardCharsets.UTF_8);
    }
}

