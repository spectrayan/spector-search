package com.spectrayan.spector.cluster;

import com.spectrayan.spector.engine.SpectorEngine;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A gRPC server that wraps a {@link SpectorEngine} as a searchable shard.
 *
 * <p>Each shard node runs an independent SpectorEngine instance and exposes
 * its search/ingest capabilities via the {@code SpectorSearchService} gRPC
 * service. The {@link ClusterCoordinator} connects to shard nodes and
 * fans out queries.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SpectorEngine engine = new SpectorEngine(config);
 *   ShardNode node = new ShardNode("shard-0", engine, 50051);
 *   node.start();  // blocks until shutdown
 * }</pre>
 */
public class ShardNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShardNode.class);

    private final String shardId;
    private final SpectorEngine engine;
    private final int port;
    private Server grpcServer;

    /**
     * Creates a shard node.
     *
     * @param shardId unique shard identifier
     * @param engine  the local SpectorEngine instance
     * @param port    gRPC listen port
     */
    public ShardNode(String shardId, SpectorEngine engine, int port) {
        this.shardId = shardId;
        this.engine = engine;
        this.port = port;
    }

    /**
     * Starts the gRPC server with the search service implementation.
     *
     * @throws IOException if the server cannot bind to the port
     */
    public void start() throws IOException {
        grpcServer = ServerBuilder.forPort(port)
                .addService(new SpectorSearchServiceImpl(shardId, engine))
                .build()
                .start();

        log.info("ShardNode '{}' started on port {} — serving {} documents",
                shardId, port, engine.documentCount());

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down ShardNode '{}'", shardId);
            close();
        }));
    }

    /**
     * Blocks until the server shuts down.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitTermination() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.awaitTermination();
        }
    }

    /** Returns the shard ID. */
    public String shardId() { return shardId; }

    /** Returns the listen port. */
    public int port() { return port; }

    /** Returns the underlying engine. */
    public SpectorEngine engine() { return engine; }

    @Override
    public void close() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                grpcServer.shutdownNow();
            }
        }
        engine.close();
        log.info("ShardNode '{}' stopped", shardId);
    }
}
