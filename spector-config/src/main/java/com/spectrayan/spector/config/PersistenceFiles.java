package com.spectrayan.spector.config;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.config.error.SpectorConfigValueException;

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
 *   <li>{@code index_shards/} — Directory for sharded index + vector files</li>
 *   </ul>
 *
 * @param indexFile      HNSW index file name
 * @param vectorsFile    memory-mapped vectors file name
 * @param documentsFile  document store file name
 * @param idMappingsFile ID mappings file name
 * @param shardDirName   subdirectory for sharded index/vector files
 */
public record PersistenceFiles(
        String indexFile,
        String vectorsFile,
        String documentsFile,
        String idMappingsFile,
        String shardDirName
) {

    /** Default file names. */
    public static final PersistenceFiles DEFAULTS = new PersistenceFiles(
            "index.spct",
            "vectors.mmap",
            "documents.dat",
            "id-mappings.dat",
            "index_shards"
    );

    public PersistenceFiles {
        if (indexFile == null || indexFile.isBlank())
            throw new SpectorConfigValueException("indexFile", "must not be blank");
        if (vectorsFile == null || vectorsFile.isBlank())
            throw new SpectorConfigValueException("vectorsFile", "must not be blank");
        if (documentsFile == null || documentsFile.isBlank())
            throw new SpectorConfigValueException("documentsFile", "must not be blank");
        if (idMappingsFile == null || idMappingsFile.isBlank())
            throw new SpectorConfigValueException("idMappingsFile", "must not be blank");
        if (shardDirName == null || shardDirName.isBlank())
            throw new SpectorConfigValueException("shardDirName", "must not be blank");
    }

    /**
     * Backward-compatible constructor (4-arg) — uses default shard directory name.
     *
     * @param indexFile      HNSW index file name
     * @param vectorsFile    vectors file name
     * @param documentsFile  document store file name
     * @param idMappingsFile ID mappings file name
     */
    public PersistenceFiles(String indexFile, String vectorsFile,
                             String documentsFile, String idMappingsFile) {
        this(indexFile, vectorsFile, documentsFile, idMappingsFile, "index_shards");
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
                props.getString("spector.persistence.files.id-mappings", DEFAULTS.idMappingsFile),
                props.getString("spector.persistence.files.shard-dir", DEFAULTS.shardDirName)
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

    /**
     * Resolves the shard directory within the given data directory.
     *
     * @param dataDir the engine data directory
     * @return path to the shard directory
     */
    public Path resolveShardDir(Path dataDir) {
        return dataDir.resolve(shardDirName);
    }
}
