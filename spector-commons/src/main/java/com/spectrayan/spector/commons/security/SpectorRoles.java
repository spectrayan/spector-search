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
 * Predefined RBAC role constants for Spector.
 *
 * <h3>Role Model</h3>
 * <p>Roles are collections of scopes. Each role grants a specific set of
 * permissions to its holder. Custom roles can be defined in the enterprise
 * layer — these predefined roles cover the most common patterns.</p>
 *
 * <h3>Predefined Roles</h3>
 * <pre>
 *   VIEWER     → memory:read + search:read
 *   AGENT      → memory:read + memory:write + search:read
 *   EDITOR     → memory:read + memory:write + search:read + search:write
 *   ADMIN      → all scopes within tenant
 *   SUPER_ADMIN → all scopes across all tenants
 * </pre>
 *
 * <h3>Namespace Access Modes</h3>
 * <p>In addition to scopes, the RBAC system defines how a subject
 * accesses namespaces:</p>
 * <ul>
 *   <li>{@link NamespaceAccessMode#OWN} — can only access its own namespace</li>
 *   <li>{@link NamespaceAccessMode#ASSIGNED} — can access explicitly listed namespaces</li>
 *   <li>{@link NamespaceAccessMode#TENANT_ALL} — can access all namespaces in the tenant</li>
 *   <li>{@link NamespaceAccessMode#GLOBAL_ALL} — can access all namespaces across all tenants</li>
 * </ul>
 *
 * @see SpectorScopes
 */
public final class SpectorRoles {

    private SpectorRoles() {}

    // ═══════════════════════════════════════════════════════════════
    // Role Name Constants
    // ═══════════════════════════════════════════════════════════════

    /** Read-only access to memories and search. Ideal for monitoring dashboards. */
    public static final String VIEWER = "viewer";

    /** AI agent role: read + write memory, read search. The default for MCP-connected agents. */
    public static final String AGENT = "agent";

    /** Human editor role: full memory + search read/write. For data engineers and content managers. */
    public static final String EDITOR = "editor";

    /** Tenant administrator: all operations within a single tenant. */
    public static final String ADMIN = "admin";

    /** Super-administrator: all operations across all tenants. Platform operators only. */
    public static final String SUPER_ADMIN = "super-admin";

    // ═══════════════════════════════════════════════════════════════
    // Role → Scope Mappings
    // ═══════════════════════════════════════════════════════════════

    /** Scopes granted to the VIEWER role. */
    public static final Set<String> VIEWER_SCOPES = Set.of(
            SpectorScopes.MEMORY_READ,
            SpectorScopes.SEARCH_READ
    );

    /** Scopes granted to the AGENT role. */
    public static final Set<String> AGENT_SCOPES = Set.of(
            SpectorScopes.MEMORY_READ,
            SpectorScopes.MEMORY_WRITE,
            SpectorScopes.SEARCH_READ
    );

    /** Scopes granted to the EDITOR role. */
    public static final Set<String> EDITOR_SCOPES = Set.of(
            SpectorScopes.MEMORY_READ,
            SpectorScopes.MEMORY_WRITE,
            SpectorScopes.SEARCH_READ,
            SpectorScopes.SEARCH_WRITE
    );

    /** Scopes granted to the ADMIN role. */
    public static final Set<String> ADMIN_SCOPES = Set.of(
            SpectorScopes.MEMORY_READ,
            SpectorScopes.MEMORY_WRITE,
            SpectorScopes.SEARCH_READ,
            SpectorScopes.SEARCH_WRITE,
            SpectorScopes.NAMESPACE_READ,
            SpectorScopes.NAMESPACE_ADMIN
    );

    /** Scopes granted to the SUPER_ADMIN role. */
    public static final Set<String> SUPER_ADMIN_SCOPES = SpectorScopes.ALL;

    /**
     * Returns the scopes for a given role name.
     *
     * @param roleName the role name (case-insensitive)
     * @return the set of scopes, or empty set if the role is not recognized
     */
    public static Set<String> scopesForRole(String roleName) {
        if (roleName == null) return Set.of();
        return switch (roleName.toLowerCase()) {
            case VIEWER -> VIEWER_SCOPES;
            case AGENT -> AGENT_SCOPES;
            case EDITOR -> EDITOR_SCOPES;
            case ADMIN -> ADMIN_SCOPES;
            case SUPER_ADMIN -> SUPER_ADMIN_SCOPES;
            default -> Set.of();
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Namespace Access Modes
    // ═══════════════════════════════════════════════════════════════

    /**
     * Defines how a subject (agent/user) accesses namespaces.
     */
    public enum NamespaceAccessMode {
        /** Can only access its own namespace (e.g., agent-claude → acme-corp/agent-claude). */
        OWN,

        /** Can access explicitly listed namespaces (e.g., [agent-claude, shared]). */
        ASSIGNED,

        /** Can access all namespaces within the tenant (tenant-admin). */
        TENANT_ALL,

        /** Can access all namespaces across all tenants (super-admin). */
        GLOBAL_ALL
    }
}
