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
package com.spectrayan.spector.bench.cognitive.model;

import java.util.List;

/**
 * The user persona definition grounding all generated conversations in a consistent character.
 *
 * <p>Maps directly to {@code persona.json} in the cognitive benchmark dataset. The persona
 * provides identity, background, relationships, interests, life history, and personality
 * traits that ensure temporal and emotional coherence across thousands of generated
 * memories.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code name} — non-empty string identifying the persona</li>
 *   <li>{@code age} — integer in range [18, 100]</li>
 *   <li>{@code occupation} — non-empty string describing the persona's profession</li>
 *   <li>{@code interests} — array of 3–20 strings representing hobbies, topics, or
 *       activities the persona engages with</li>
 *   <li>{@code lifeContext} — string of 50–2000 characters describing the persona's
 *       current life situation (living arrangements, recent events, goals)</li>
 *   <li>{@code personalityTraits} — array of 3–10 strings from a recognized trait
 *       taxonomy (e.g., Big Five facets, HEXACO dimensions)</li>
 *   <li>{@code companionRelationship} — string of 50–500 characters describing the
 *       user's relationship with the AI companion (duration, usage patterns, tone)</li>
 * </ul>
 *
 * @param name                   the persona's full name
 * @param age                    the persona's age (18–100)
 * @param occupation             the persona's profession or role
 * @param interests              hobbies and topics of interest (3–20 entries)
 * @param lifeContext            current life situation description (50–2000 characters)
 * @param personalityTraits      personality trait descriptors (3–10 entries)
 * @param companionRelationship  description of relationship with AI companion (50–500 characters)
 */
public record PersonaDef(
        String name,
        int age,
        String occupation,
        List<String> interests,
        String lifeContext,
        List<String> personalityTraits,
        String companionRelationship
) {}
