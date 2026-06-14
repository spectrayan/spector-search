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
package com.spectrayan.spector.commons.security;

import java.util.Set;

/**
 * OAuth 2.1 scope constants for Spector RBAC.
 *
 * <h3>Scope Taxonomy</h3>
 * <p>Scopes follow the {@code spector:{resource}:{action}} pattern.
 * Each scope grants access to a specific category of operations.</p>
 *
 * <h3>Usage</h3>
 * <ul>
 *   <li><b>MCP tools:</b> Each tool declares its required scopes via
 *       {@code McpToolHandler.requiredScopes()}</li>
 *   <li><b>Enterprise:</b> JWT tokens carry scopes in the {@code scopes} claim.
 *       The enterprise layer filters tools and validates requests against these scopes.</li>
 *   <li><b>OSS:</b> No scope enforcement — all tools are accessible.</li>
 * </ul>
 *
 * <h3>Scope Hierarchy</h3>
 * <pre>
 *   spector:memory:read     → query, browse, inspect, introspect, export
 *   spector:memory:write    → remember, forget, reinforce, suppress, resolve
 *   spector:search:read     → semantic search, hybrid search, RAG
 *   spector:search:write    → ingest documents, delete documents
 *   spector:namespace:read  → list namespaces, view config
 *   spector:namespace:admin → create/delete namespaces, modify quotas
 *   spector:admin           → full system administration (super-admin)
 * </pre>
 *
 * @see SpectorRoles
 */
public final class SpectorScopes {

    private SpectorScopes() {}

    /** Scope prefix for all Spector scopes. */
    public static final String PREFIX = "spector:";

    // ═══════════════════════════════════════════════════════════════
    // Memory Scopes
    // ═══════════════════════════════════════════════════════════════

    /** Read cognitive memories: recall, browse, inspect, introspect, why-not, export, status. */
    public static final String MEMORY_READ = "spector:memory:read";

    /** Write cognitive memories: remember, forget, reinforce, suppress, resolve, scratchpad, reminder. */
    public static final String MEMORY_WRITE = "spector:memory:write";

    // ═══════════════════════════════════════════════════════════════
    // Search / Engine Scopes
    // ═══════════════════════════════════════════════════════════════

    /** Read from search index: semantic search, hybrid search, RAG queries. */
    public static final String SEARCH_READ = "spector:search:read";

    /** Write to search index: document ingestion, document deletion. */
    public static final String SEARCH_WRITE = "spector:search:write";

    // ═══════════════════════════════════════════════════════════════
    // Namespace Scopes (Enterprise)
    // ═══════════════════════════════════════════════════════════════

    /** Read namespace metadata: list namespaces, view config and quotas. */
    public static final String NAMESPACE_READ = "spector:namespace:read";

    /** Administer namespaces: create, delete, modify quotas, set read-only. */
    public static final String NAMESPACE_ADMIN = "spector:namespace:admin";

    // ═══════════════════════════════════════════════════════════════
    // System Scopes (Enterprise)
    // ═══════════════════════════════════════════════════════════════

    /** Full system administration: tenant management, global config, audit. */
    public static final String ADMIN = "spector:admin";

    // ═══════════════════════════════════════════════════════════════
    // Predefined Scope Sets
    // ═══════════════════════════════════════════════════════════════

    /** All memory scopes (read + write). */
    public static final Set<String> ALL_MEMORY = Set.of(MEMORY_READ, MEMORY_WRITE);

    /** All search scopes (read + write). */
    public static final Set<String> ALL_SEARCH = Set.of(SEARCH_READ, SEARCH_WRITE);

    /** All scopes — super-admin. */
    public static final Set<String> ALL = Set.of(
            MEMORY_READ, MEMORY_WRITE,
            SEARCH_READ, SEARCH_WRITE,
            NAMESPACE_READ, NAMESPACE_ADMIN,
            ADMIN
    );
}
