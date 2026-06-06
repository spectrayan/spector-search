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
package com.spectrayan.spector.memory.metamemory;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Memory introspection engine — lets the agent reason about what it knows.
 *
 * <h3>Biological Analog: Metacognition / Metamemory</h3>
 * <p>Knowledge about your own memory capabilities. You <em>know</em> you're bad at
 * remembering names but good at faces. This self-awareness lets you compensate —
 * you write names down because you know you'll forget.</p>
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li><b>Confidence:</b> How well does the agent know a topic? (based on count + reinforcement)</li>
 *   <li><b>Gaps:</b> What related topics have zero memories? (via Hebbian co-activation cross-reference)</li>
 *   <li><b>Staleness:</b> How old is the knowledge? (may be outdated)</li>
 *   <li><b>Recall frequency:</b> How often is this knowledge used?</li>
 *   <li><b>Recommendation:</b> Actionable advice for the agent</li>
 * </ul>
 *
 * <h3>Gap Detection</h3>
 * <p>Uses {@link CoActivationTracker} to find topics that frequently co-occur with
 * the queried tags but have zero memories in the current result set. These are
 * "knowledge holes" — related domains where the agent lacks information.</p>
 *
 * <h3>Value</h3>
 * <p>Instead of hallucinating, the agent can say: "I don't have strong memories
 * about Kubernetes RBAC — let me ask you about that."</p>
 */
public final class MemoryIntrospector {

    private static final Logger log = LoggerFactory.getLogger(MemoryIntrospector.class);

    /** Maximum number of gaps to report. */
    private static final int MAX_GAPS = 10;

    /** Maximum number of co-activated tags to consider per result tag. */
    private static final int CO_ACTIVATION_DEPTH = 5;

    private final CoActivationTracker coActivationTracker;

    /**
     * Creates a memory introspector with Hebbian co-activation support for gap detection.
     *
     * @param coActivationTracker the tracker recording tag co-occurrence data
     */
    public MemoryIntrospector(CoActivationTracker coActivationTracker) {
        this.coActivationTracker = coActivationTracker;
    }

    /**
     * Creates a memory introspector without co-activation support (gaps will be empty).
     */
    public MemoryIntrospector() {
        this(null);
    }

    /**
     * Analyzes a set of recall results to produce a metamemory insight.
     *
     * <p>Call this with the results from a broad recall query about a topic.</p>
     *
     * @param query   the topic being introspected
     * @param results recall results for the topic
     * @return aggregated insight about the agent's knowledge
     */
    public MemoryInsight analyze(String query, List<CognitiveResult> results) {
        if (results == null || results.isEmpty()) {
            return MemoryInsight.empty(query);
        }

        int count = results.size();

        // Average importance
        float sumImportance = 0f;
        float sumValence = 0f;
        float sumAgeDays = 0f;
        float sumagentRecallCount = 0f;
        int reinforcedCount = 0;

        for (CognitiveResult r : results) {
            sumImportance += r.importance();
            sumValence += r.valence();
            sumAgeDays += r.ageDays();
            sumagentRecallCount += r.agentRecallCount();
            if (r.agentRecallCount() > 0) reinforcedCount++;
        }

        float avgImportance = sumImportance / count;
        float avgValence = sumValence / count;
        float avgAgeDays = sumAgeDays / count;
        float avgRecalls = sumagentRecallCount / count;

        // Confidence: based on memory count, reinforcement ratio, and importance
        float countFactor = Math.min(1.0f, count / 20.0f); // saturates at 20 memories
        float reinforcementFactor = count > 0 ? (float) reinforcedCount / count : 0f;
        float importanceFactor = Math.min(1.0f, avgImportance / 5.0f);
        float confidence = (countFactor * 0.4f + reinforcementFactor * 0.3f + importanceFactor * 0.3f);

        // Staleness: based on average age
        float staleness;
        if (avgAgeDays < 1) staleness = 0.0f;
        else if (avgAgeDays < 7) staleness = 0.2f;
        else if (avgAgeDays < 30) staleness = 0.5f;
        else if (avgAgeDays < 90) staleness = 0.7f;
        else staleness = 0.9f;

        // Approximate recalls per day
        float recallsPerDay = avgAgeDays > 0 ? avgRecalls / avgAgeDays : avgRecalls;

        // Recommendation
        String recommendation = buildRecommendation(query, confidence, staleness, count);

        // Gap detection via Hebbian co-activation cross-reference
        String[] gaps = detectGaps(results);

        MemoryInsight insight = new MemoryInsight(query, count, avgImportance, avgValence,
                avgAgeDays, confidence, gaps, staleness, recallsPerDay, recommendation);

        log.debug("Introspection for '{}': confidence={}, staleness={}, count={}, gaps={}",
                query, confidence, staleness, count, gaps.length);

        return insight;
    }

    /**
     * Detects knowledge gaps by cross-referencing tags in the result set with
     * Hebbian co-activation data.
     *
     * <p>For each tag present in the results, queries the co-activation tracker for
     * related tags. Tags that are strongly co-activated but absent from the result set
     * are identified as gaps — topics the agent should know about but doesn't.</p>
     *
     * @param results the recall results to analyze
     * @return array of gap topic names (empty if no co-activation data available)
     */
    private String[] detectGaps(List<CognitiveResult> results) {
        if (coActivationTracker == null || coActivationTracker.pairCount() == 0) {
            return new String[0];
        }

        // Collect all tags present in the result set
        Set<String> presentTags = new LinkedHashSet<>();
        for (CognitiveResult r : results) {
            if (r.synapticTags() != null) {
                for (String tag : r.synapticTags()) {
                    presentTags.add(tag);
                }
            }
        }

        if (presentTags.isEmpty()) {
            return new String[0];
        }

        // Find co-activated tags that are NOT in the present set → these are gaps
        Set<String> gaps = new LinkedHashSet<>();
        for (String tag : presentTags) {
            List<String> associated = coActivationTracker.getAssociatedTags(tag, CO_ACTIVATION_DEPTH);
            for (String candidate : associated) {
                if (!presentTags.contains(candidate)) {
                    gaps.add(candidate);
                    if (gaps.size() >= MAX_GAPS) break;
                }
            }
            if (gaps.size() >= MAX_GAPS) break;
        }

        return gaps.toArray(String[]::new);
    }

    private String buildRecommendation(String query, float confidence,
                                        float staleness, int count) {
        if (count == 0) {
            return "No memories found for '" + query + "'. Consider asking the user.";
        }
        if (confidence < 0.3f) {
            return "Low confidence on '" + query + "'. Knowledge is sparse — consider researching.";
        }
        if (staleness > 0.7f) {
            return "Knowledge about '" + query + "' may be outdated (avg age > 30 days). Consider refreshing.";
        }
        if (confidence > 0.7f && staleness < 0.3f) {
            return "High confidence on '" + query + "'. Recent and well-reinforced knowledge.";
        }
        return "Moderate confidence on '" + query + "'. " + count + " memories found.";
    }
}

