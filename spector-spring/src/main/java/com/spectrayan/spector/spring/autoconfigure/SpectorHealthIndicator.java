/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.spring.autoconfigure;

import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health indicator for Spector.
 *
 * <p>Reports engine status, document count, SIMD capability, and optional
 * memory tier counts at {@code /actuator/health}.</p>
 *
 * <h3>Example Output</h3>
 * <pre>{@code
 *   "spector": {
 *     "status": "UP",
 *     "details": {
 *       "engine.documents": 42000,
 *       "engine.gpu": false,
 *       "engine.reranker": false,
 *       "engine.embedding": "nomic-embed-text",
 *       "simd": "AVX-512 (512-bit, preferred species: 16 floats)",
 *       "memory.total": 1500,
 *       "memory.episodic": 800,
 *       "memory.semantic": 700
 *     }
 *   }
 * }</pre>
 */
@Component
@ConditionalOnClass({HealthIndicator.class, SpectorEngine.class})
public class SpectorHealthIndicator implements HealthIndicator {

    private final SpectorEngine engine;
    private final SpectorMemory memory; // nullable

    public SpectorHealthIndicator(SpectorEngine engine,
                                   ObjectProvider<SpectorMemory> memoryProvider) {
        this.engine = engine;
        this.memory = memoryProvider.getIfAvailable();
    }

    @Override
    public Health health() {
        try {
            var builder = Health.up()
                    .withDetail("engine.documents", engine.documentCount())
                    .withDetail("engine.gpu", engine.isGpuActive())
                    .withDetail("engine.reranker", engine.isRerankerActive())
                    .withDetail("engine.embedding",
                            engine.hasEmbeddingProvider()
                                    ? engine.embeddingProvider().modelName()
                                    : "none")
                    .withDetail("simd", SimdCapability.report());

            if (memory != null) {
                builder.withDetail("memory.total", memory.totalMemories());
                builder.withDetail("memory.working",
                        memory.memoryCount(com.spectrayan.spector.memory.MemoryType.WORKING));
                builder.withDetail("memory.episodic",
                        memory.memoryCount(com.spectrayan.spector.memory.MemoryType.EPISODIC));
                builder.withDetail("memory.semantic",
                        memory.memoryCount(com.spectrayan.spector.memory.MemoryType.SEMANTIC));
                builder.withDetail("memory.procedural",
                        memory.memoryCount(com.spectrayan.spector.memory.MemoryType.PROCEDURAL));
            }

            return builder.build();
        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .build();
        }
    }
}
