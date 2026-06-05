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
package com.spectrayan.spector.bench.cognitive.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.PersonaDef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and validates a persona definition from a JSON file.
 *
 * <p>The persona file must conform to the cognitive benchmark schema, providing
 * all required fields with values within specified constraints. Validation errors
 * are collected and reported as a single exception.</p>
 *
 * <h3>Validation Rules</h3>
 * <ul>
 *   <li>{@code name} — non-empty</li>
 *   <li>{@code age} — integer in [18, 100]</li>
 *   <li>{@code occupation} — non-empty</li>
 *   <li>{@code interests} — array with 3–20 entries</li>
 *   <li>{@code lifeContext} — 50–2000 characters</li>
 *   <li>{@code personalityTraits} — array with 3–10 entries</li>
 *   <li>{@code companionRelationship} — 50–500 characters</li>
 * </ul>
 */
public final class PersonaLoader {

    private static final Logger log = LoggerFactory.getLogger(PersonaLoader.class);

    private final ObjectMapper mapper;

    public PersonaLoader() {
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Loads a persona definition from the specified JSON file.
     *
     * @param personaFile path to the persona.json file
     * @return validated persona definition
     * @throws PersonaValidationException if the file cannot be read or fails validation
     */
    public PersonaDef load(Path personaFile) {
        if (!Files.exists(personaFile)) {
            throw new PersonaValidationException("Persona file not found: " + personaFile);
        }

        PersonaDef persona;
        try {
            String json = Files.readString(personaFile);
            JsonNode node = mapper.readTree(json);

            String name = node.has("name") ? node.get("name").asText() : "";
            int age = node.has("age") ? node.get("age").asInt() : 0;
            String occupation = node.has("occupation") ? node.get("occupation").asText() : "";

            List<String> interests = new ArrayList<>();
            JsonNode interestsNode = node.get("interests");
            if (interestsNode != null && interestsNode.isArray()) {
                for (JsonNode item : interestsNode) {
                    interests.add(item.asText());
                }
            }

            String lifeContext = "";
            if (node.has("life_context")) {
                lifeContext = node.get("life_context").asText();
            } else if (node.has("lifeContext")) {
                lifeContext = node.get("lifeContext").asText();
            }

            List<String> personalityTraits = new ArrayList<>();
            JsonNode traitsNode = node.has("personality_traits") ? node.get("personality_traits") : node.get("personalityTraits");
            if (traitsNode != null && traitsNode.isArray()) {
                for (JsonNode item : traitsNode) {
                    personalityTraits.add(item.asText());
                }
            }

            String companionRelationship = "";
            if (node.has("companion_relationship")) {
                companionRelationship = node.get("companion_relationship").asText();
            } else if (node.has("companionRelationship")) {
                companionRelationship = node.get("companionRelationship").asText();
            }

            persona = new PersonaDef(name, age, occupation, interests, lifeContext,
                    personalityTraits, companionRelationship);
        } catch (IOException e) {
            throw new PersonaValidationException("Failed to read persona file: " + personaFile, e);
        } catch (PersonaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new PersonaValidationException("Failed to parse persona JSON: " + e.getMessage(), e);
        }

        validate(persona);
        log.info("Loaded persona: name={}, age={}, occupation={}",
                persona.name(), persona.age(), persona.occupation());
        return persona;
    }

    /**
     * Validates all persona fields against schema constraints.
     *
     * @param persona the parsed persona definition
     * @throws PersonaValidationException if any validation rules are violated
     */
    private void validate(PersonaDef persona) {
        List<String> errors = new ArrayList<>();

        if (persona.name() == null || persona.name().isBlank()) {
            errors.add("name must be non-empty");
        }
        if (persona.age() < 18 || persona.age() > 100) {
            errors.add("age must be in [18, 100], got: " + persona.age());
        }
        if (persona.occupation() == null || persona.occupation().isBlank()) {
            errors.add("occupation must be non-empty");
        }
        if (persona.interests() == null || persona.interests().size() < 3 || persona.interests().size() > 20) {
            int size = persona.interests() == null ? 0 : persona.interests().size();
            errors.add("interests must have 3–20 entries, got: " + size);
        }
        if (persona.lifeContext() == null || persona.lifeContext().length() < 50
                || persona.lifeContext().length() > 2000) {
            int len = persona.lifeContext() == null ? 0 : persona.lifeContext().length();
            errors.add("lifeContext must be 50–2000 characters, got: " + len);
        }
        if (persona.personalityTraits() == null || persona.personalityTraits().size() < 3
                || persona.personalityTraits().size() > 10) {
            int size = persona.personalityTraits() == null ? 0 : persona.personalityTraits().size();
            errors.add("personalityTraits must have 3–10 entries, got: " + size);
        }
        if (persona.companionRelationship() == null || persona.companionRelationship().length() < 50
                || persona.companionRelationship().length() > 500) {
            int len = persona.companionRelationship() == null ? 0 : persona.companionRelationship().length();
            errors.add("companionRelationship must be 50–500 characters, got: " + len);
        }

        if (!errors.isEmpty()) {
            throw new PersonaValidationException(
                    "Persona validation failed with " + errors.size() + " error(s):\n  - "
                            + String.join("\n  - ", errors));
        }
    }

    /**
     * Exception thrown when persona loading or validation fails.
     */
    public static class PersonaValidationException extends RuntimeException {
        public PersonaValidationException(String message) {
            super(message);
        }

        public PersonaValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
