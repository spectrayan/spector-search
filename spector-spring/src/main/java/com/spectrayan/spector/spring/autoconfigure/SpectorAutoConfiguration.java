package com.spectrayan.spector.spring.autoconfigure;

import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.DefaultSpectorMemory;
import com.spectrayan.spector.memory.MemoryPersistenceMode;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.metrics.MeteredSpectorEngine;
import com.spectrayan.spector.metrics.MeteredSpectorMemory;
import com.spectrayan.spector.metrics.SpectorMetrics;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Spring Boot auto-configuration for embedded Spector Search.
 *
 * <p>Automatically creates and wires {@link SpectorEngine} and optionally
 * {@link SpectorMemory} beans when Spector is on the classpath. If a
 * {@link MeterRegistry} is available (e.g., from Spring Boot Actuator),
 * the beans are automatically wrapped with metered decorators for
 * observability through {@code /actuator/metrics}.</p>
 *
 * <h3>Usage</h3>
 * <p>Add {@code spector-spring} to your Spring Boot application's dependencies.
 * Configure via {@code application.yml}:</p>
 * <pre>{@code
 *   spector:
 *     engine:
 *       dimensions: 768
 *       capacity: 100000
 *     metrics:
 *       enabled: true
 * }</pre>
 *
 * <h3>Bean Hierarchy</h3>
 * <ul>
 *   <li>{@code SpectorEngine} — metered wrapper (if metrics enabled) around {@code DefaultSpectorEngine}</li>
 *   <li>{@code SpectorMemory} — metered wrapper (if metrics enabled) around {@code DefaultSpectorMemory}</li>
 *   <li>{@code SpectorVectorStore} — Spring AI VectorStore bridge (if Spring AI on classpath)</li>
 * </ul>
 *
 * @see SpectorConfigProperties
 */
@AutoConfiguration
@EnableConfigurationProperties(SpectorConfigProperties.class)
@ConditionalOnClass(SpectorEngine.class)
public class SpectorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpectorAutoConfiguration.class);

    /**
     * Creates the core {@link SpectorEngine} bean.
     *
     * <p>If a {@link MeterRegistry} is available and metrics are enabled,
     * the engine is wrapped with a {@link MeteredSpectorEngine} decorator.
     * Also initializes the global {@link SpectorMetrics} registry.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    SpectorEngine spectorEngine(SpectorConfigProperties props,
                                 ObjectProvider<EmbeddingProvider> embedderProvider,
                                 ObjectProvider<MeterRegistry> registryProvider) {

        SpectorConfig config = props.toEngineConfig();
        EmbeddingProvider embedder = embedderProvider.getIfAvailable();

        DefaultSpectorEngine raw = new DefaultSpectorEngine(config, embedder);
        log.info("SpectorEngine auto-configured: dims={}, capacity={}, embedding={}",
                config.dimensions(), config.capacity(),
                embedder != null ? embedder.modelName() : "none");

        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null && props.getMetrics().isEnabled()) {
            SpectorMetrics.init(registry);
            log.info("Spector metrics enabled via Spring MeterRegistry");
            return new MeteredSpectorEngine(raw, registry);
        }

        return raw;
    }

    /**
     * Creates the {@link SpectorMemory} bean when memory is enabled.
     *
     * <p>Optionally wrapped with {@link MeteredSpectorMemory} when metrics
     * are available.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spector.memory", name = "enabled", havingValue = "true")
    SpectorMemory spectorMemory(SpectorConfigProperties props,
                                 ObjectProvider<EmbeddingProvider> embedderProvider,
                                 ObjectProvider<MeterRegistry> registryProvider) {

        var memoryProps = props.getMemory();
        EmbeddingProvider embedder = embedderProvider.getIfAvailable();

        if (embedder == null) {
            throw new SpectorInternalException(ErrorCode.ARGUMENT_NULL, "EmbeddingProvider bean (configure provider or set spector.memory.enabled=false)");
        }

        var builder = DefaultSpectorMemory.builder()
                .dimensions(memoryProps.getDimensions())
                .embeddingProvider(embedder)
                .persistenceMode(MemoryPersistenceMode.valueOf(memoryProps.getPersistenceMode()))
                .semanticCapacity(memoryProps.getCapacity());

        if (memoryProps.getPersistencePath() != null) {
            builder.persistence(Path.of(memoryProps.getPersistencePath()));
        }

        SpectorMemory raw = builder.build();
        log.info("SpectorMemory auto-configured: dims={}, persistence={}, path={}",
                memoryProps.getDimensions(), memoryProps.getPersistenceMode(),
                memoryProps.getPersistencePath());

        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null && props.getMetrics().isEnabled()) {
            return new MeteredSpectorMemory(raw, registry);
        }

        return raw;
    }
}
