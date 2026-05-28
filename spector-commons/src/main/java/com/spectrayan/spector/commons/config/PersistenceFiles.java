package com.spectrayan.spector.commons.config;

import java.nio.file.Path;

/**
 * Centralized persistence file names for the Spector engine.
 *
 * <p>Replaces hardcoded file names like {@code "index.spct"}, {@code "vectors.mmap"},
 * etc. scattered across engine factories. File names are configurable via
 * {@link SpectorProperties} under the {@code spector.persistence.files} namespace.</p>
 *
 * <h3>Default File Names</h3>
 * <ul>
 *   <li>{@code index.spct} — HNSW graph structure</li>
 *   <li>{@code vectors.mmap} — Memory-mapped raw float32 vectors</li>
 *   <li>{@code documents.dat} — Document text content</li>
 *   <li>{@code id-mappings.dat} — String ID → integer index mappings</li>
 * </ul>
 *
 * @param indexFile      HNSW index file name
 * @param vectorsFile    memory-mapped vectors file name
 * @param documentsFile  document store file name
 * @param idMappingsFile ID mappings file name
 */
public record PersistenceFiles(
        String indexFile,
        String vectorsFile,
        String documentsFile,
        String idMappingsFile
) {

    /** Default file names. */
    public static final PersistenceFiles DEFAULTS = new PersistenceFiles(
            "index.spct",
            "vectors.mmap",
            "documents.dat",
            "id-mappings.dat"
    );

    public PersistenceFiles {
        if (indexFile == null || indexFile.isBlank())
            throw new IllegalArgumentException("indexFile must not be blank");
        if (vectorsFile == null || vectorsFile.isBlank())
            throw new IllegalArgumentException("vectorsFile must not be blank");
        if (documentsFile == null || documentsFile.isBlank())
            throw new IllegalArgumentException("documentsFile must not be blank");
        if (idMappingsFile == null || idMappingsFile.isBlank())
            throw new IllegalArgumentException("idMappingsFile must not be blank");
    }

    /**
     * Creates a {@link PersistenceFiles} from configuration properties.
     *
     * @param props the configuration properties
     * @return persistence file names loaded from config or defaults
     */
    public static PersistenceFiles from(SpectorProperties props) {
        return new PersistenceFiles(
                props.getString("spector.persistence.files.index", DEFAULTS.indexFile),
                props.getString("spector.persistence.files.vectors", DEFAULTS.vectorsFile),
                props.getString("spector.persistence.files.documents", DEFAULTS.documentsFile),
                props.getString("spector.persistence.files.id-mappings", DEFAULTS.idMappingsFile)
        );
    }

    /**
     * Resolves the index file path within the given data directory.
     */
    public Path resolveIndex(Path dataDir) {
        return dataDir.resolve(indexFile);
    }

    /**
     * Resolves the vectors file path within the given data directory.
     */
    public Path resolveVectors(Path dataDir) {
        return dataDir.resolve(vectorsFile);
    }

    /**
     * Resolves the documents file path within the given data directory.
     */
    public Path resolveDocuments(Path dataDir) {
        return dataDir.resolve(documentsFile);
    }

    /**
     * Resolves the ID mappings file path within the given data directory.
     */
    public Path resolveIdMappings(Path dataDir) {
        return dataDir.resolve(idMappingsFile);
    }
}
