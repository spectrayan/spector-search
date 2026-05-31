/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.graph;

import com.spectrayan.spector.embed.TextGenerationProvider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LlmEntityExtractor response parsing and error handling.
 */
class LlmEntityExtractorTest {

    @Test
    void parsesSimpleResponse() {
        String response = """
                ENTITY: Alice | PERSON
                ENTITY: Project Alpha | PROJECT
                RELATION: Alice | MANAGES | Project Alpha
                """;

        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                return response;
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        List<ExtractedEntity> entities = extractor.extract("test-id", "Alice manages Project Alpha");

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).name()).isEqualTo("Alice");
        assertThat(entities.get(0).type()).isEqualTo(EntityType.PERSON);
        assertThat(entities.get(0).relations()).hasSize(1);
        assertThat(entities.get(0).relations().get(0).relationType()).isEqualTo(RelationType.MANAGES);
        assertThat(entities.get(0).relations().get(0).targetEntityName()).isEqualTo("Project Alpha");

        assertThat(entities.get(1).name()).isEqualTo("Project Alpha");
        assertThat(entities.get(1).type()).isEqualTo(EntityType.PROJECT);
    }

    @Test
    void handlesEmptyResponse() {
        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                return "";
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        List<ExtractedEntity> entities = extractor.extract("test-id", "some text");

        assertThat(entities).isEmpty();
    }

    @Test
    void handlesNullProvider() {
        LlmEntityExtractor extractor = new LlmEntityExtractor(null);

        assertThat(extractor.isAvailable()).isFalse();
        assertThat(extractor.extract("test", "text")).isEmpty();
    }

    @Test
    void handlesUnavailableProvider() {
        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                throw new RuntimeException("Should not be called");
            }
            @Override
            public boolean isAvailable() {
                return false;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        assertThat(extractor.extract("test", "text")).isEmpty();
    }

    @Test
    void handlesProviderException() {
        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                throw new RuntimeException("API failure");
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        List<ExtractedEntity> entities = extractor.extract("test-id", "text");

        assertThat(entities).isEmpty(); // graceful degradation
    }

    @Test
    void parsesUnknownEntityType() {
        String response = "ENTITY: Widget | GADGET\n";

        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                return response;
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        List<ExtractedEntity> entities = extractor.extract("test", "text");

        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).type()).isEqualTo(EntityType.OTHER);
    }

    @Test
    void respectsMaxEntitiesLimit() {
        StringBuilder response = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            response.append("ENTITY: Entity" + i + " | PERSON\n");
        }

        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                return response.toString();
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider, 5, 10);
        List<ExtractedEntity> entities = extractor.extract("test", "text");

        assertThat(entities).hasSize(5);
    }

    @Test
    void multipleRelationsForSameEntity() {
        String response = """
                ENTITY: Alice | PERSON
                ENTITY: Bob | PERSON
                ENTITY: Acme | ORGANIZATION
                RELATION: Alice | MANAGES | Bob
                RELATION: Alice | WORKS_ON | Acme
                """;

        TextGenerationProvider mockProvider = new TextGenerationProvider() {
            @Override
            public String generate(String prompt) {
                return response;
            }
            @Override
            public boolean isAvailable() {
                return true;
            }
            @Override
            public String modelName() {
                return "test-mock";
            }
        };

        LlmEntityExtractor extractor = new LlmEntityExtractor(mockProvider);
        List<ExtractedEntity> entities = extractor.extract("test", "text");

        assertThat(entities).hasSize(3);
        // Alice should have 2 relations
        var alice = entities.get(0);
        assertThat(alice.relations()).hasSize(2);
    }
}
