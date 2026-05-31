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

/**
 * Entity types for the knowledge graph.
 *
 * <p>Entities extracted from memory text are classified into these categories
 * to enable typed traversal and filtering in the entity-relationship graph.</p>
 *
 * <h3>Category Groups</h3>
 * <ul>
 *   <li><b>People &amp; Org:</b> PERSON, ORGANIZATION, TEAM, ROLE</li>
 *   <li><b>Projects &amp; Products:</b> PROJECT, PRODUCT, TASK</li>
 *   <li><b>Knowledge:</b> CONCEPT, TOPIC, SKILL, DECISION</li>
 *   <li><b>Tech:</b> TECHNOLOGY, TOOL, API, ARTIFACT</li>
 *   <li><b>World:</b> EVENT, LOCATION, DATE_TIME</li>
 *   <li><b>Process &amp; Data:</b> PROCESS, METRIC, DOCUMENT</li>
 *   <li><b>Catch-all:</b> OTHER</li>
 * </ul>
 */
public enum EntityType {

    // ── People & Organizations ──
    /** A person: user, colleague, customer, author, etc. */
    PERSON,
    /** A company, institution, government body, or formal organization. */
    ORGANIZATION,
    /** A team, squad, department, group, or committee. */
    TEAM,
    /** A job title, role, or position (e.g., "tech lead", "reviewer"). */
    ROLE,

    // ── Projects & Products ──
    /** A project, initiative, or workstream. */
    PROJECT,
    /** A software product, service, or SaaS tool. */
    PRODUCT,
    /** A task, ticket, issue, bug, or action item. */
    TASK,

    // ── Knowledge & Decisions ──
    /** An abstract concept, idea, or theory. */
    CONCEPT,
    /** A knowledge domain, subject area, or discipline. */
    TOPIC,
    /** A skill, competency, or expertise area. */
    SKILL,
    /** An architectural decision, ADR, or policy choice. */
    DECISION,

    // ── Technology ──
    /** A programming language, framework, library, or platform. */
    TECHNOLOGY,
    /** A tool, utility, or instrument. */
    TOOL,
    /** An API, endpoint, interface, or protocol. */
    API,
    /** A code file, commit, branch, PR, or configuration artifact. */
    ARTIFACT,

    // ── World ──
    /** An event, meeting, conference, incident, or milestone. */
    EVENT,
    /** A physical or virtual location, address, or region. */
    LOCATION,
    /** A specific date, time, period, or deadline. */
    DATE_TIME,

    // ── Process & Data ──
    /** A workflow, pipeline, methodology, or procedure. */
    PROCESS,
    /** A KPI, measurement, quantity, or metric. */
    METRIC,
    /** A document, file, paper, article, or report. */
    DOCUMENT,

    // ── Catch-all ──
    /** Any entity that doesn't fit the above categories. */
    OTHER
}
