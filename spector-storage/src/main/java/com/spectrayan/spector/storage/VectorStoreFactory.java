package com.spectrayan.spector.storage;


import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.config.PersistenceMode;
import com.spectrayan.spector.config.PersistenceFiles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Factory Method pattern for creating {@link VectorStore} instances.
 *
 * <p>Selects the appropriate vector store implementation based on the
 * configured {@link PersistenceMode}. New store types can be added
 * by extending this factory — without modifying the engine.</p>
 *
 * <h3>Supported Modes</h3>
 * <ul>
 *   <li>{@link PersistenceMode#IN_MEMORY} → {@link InMemoryVectorStore} (off-heap Panama segment)</li>
 *   <li>{@link PersistenceMode#DISK} → {@link MappedVectorStore} (memory-mapped file)</li>
 * </ul>
 */
public class VectorStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFactory.class);
    private final PersistenceFiles persistenceFiles;

    public VectorStoreFactory() {
        this(PersistenceFiles.DEFAULTS);
    }

    public VectorStoreFactory(PersistenceFiles persistenceFiles) {
        this.persistenceFiles = persistenceFiles;
    }

    /**
     * Creates a {@link VectorStore} based on the engine configuration.
     *
     * @param config the engine configuration
     * @return a new vector store
     */
    public VectorStore create(SpectorConfig config) {
        return switch (config.persistenceMode()) {
            case IN_MEMORY -> createInMemory(config);
            case DISK -> createMapped(config);
        };
    }

    private VectorStore createInMemory(SpectorConfig config) {
        log.info("Creating InMemoryVectorStore: dims={}, capacity={}",
                config.dimensions(), config.capacity());
        return new InMemoryVectorStore(config.dimensions(), config.capacity());
    }

    private VectorStore createMapped(SpectorConfig config) {
        Path shardDir = persistenceFiles.resolveShardDir(config.dataDirectory());
        int nodesPerShard = config.effectiveNodesPerShard();
        log.info("Creating ShardedMappedVectorStore: dims={}, capacity={}, nodesPerShard={}, dir={}",
                config.dimensions(), config.capacity(), nodesPerShard, shardDir);
        try {
            return new ShardedMappedVectorStore(shardDir, config.dimensions(),
                    config.capacity(), nodesPerShard);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create sharded vector store: " + shardDir, e);
        }
    }
}
