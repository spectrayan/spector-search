package com.spectrayan.spector.memory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Runtime configuration for enabling/disabling cognitive profiles.
 *
 * <h3>Design Philosophy</h3>
 * <p>This is an <b>operational configuration</b>, not a licensing gate.
 * Spector is distributed under the Business Source License (BSL 1.1) —
 * commercial use restrictions are enforced by the license itself, not by
 * code-level feature gates that any user with the source can bypass.</p>
 *
 * <h3>Why Configuration, Not Licensing?</h3>
 * <ul>
 *   <li>BSL handles commercial restriction — code-level gates are security theater</li>
 *   <li>Users may want to disable profiles for safety, compliance, or resource reasons</li>
 *   <li>SaaS/cloud deployments can configure available profiles per-tenant</li>
 *   <li>Self-hosted users get full functionality — the BSL license governs their usage</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // Default: all profiles enabled
 *   var config = CognitiveProfileConfig.allEnabled();
 *
 *   // Operational restriction: only allow specific profiles
 *   var config = CognitiveProfileConfig.only(
 *       CognitiveProfile.BALANCED,
 *       CognitiveProfile.DEBUGGING,
 *       CognitiveProfile.HYPERFOCUS);
 *
 *   // Validate before use
 *   CognitiveProfile profile = config.validate(CognitiveProfile.HYPERFOCUS); // → HYPERFOCUS
 *   CognitiveProfile blocked = config.validate(CognitiveProfile.THE_EXECUTOR); // → BALANCED
 *
 *   // Strict mode: throws instead of falling back
 *   config.requireEnabled(CognitiveProfile.THE_EXECUTOR); // throws IllegalArgumentException
 * }</pre>
 *
 * <h3>Presets</h3>
 * <table>
 *   <tr><th>Preset</th><th>Profiles</th><th>Use Case</th></tr>
 *   <tr><td>{@link #allEnabled()}</td><td>All 11</td><td>Default / self-hosted</td></tr>
 *   <tr><td>{@link #coreOnly()}</td><td>5 core</td><td>Minimal / embedded</td></tr>
 *   <tr><td>{@link #withNeurodivergent()}</td><td>Core + 3 neuro</td><td>Research / creative</td></tr>
 *   <tr><td>{@link #only(CognitiveProfile...)}</td><td>Custom</td><td>SaaS tenant config</td></tr>
 * </table>
 */
public final class CognitiveProfileConfig {

    /** Core profiles: always safe, low resource impact. */
    private static final Set<CognitiveProfile> CORE_PROFILES = EnumSet.of(
            CognitiveProfile.BALANCED,
            CognitiveProfile.EXPLORING,
            CognitiveProfile.DEBUGGING,
            CognitiveProfile.RECALLING,
            CognitiveProfile.CRITICAL
    );

    /** Core + neurodivergent profiles. */
    private static final Set<CognitiveProfile> NEURODIVERGENT_PROFILES;
    static {
        NEURODIVERGENT_PROFILES = EnumSet.copyOf(CORE_PROFILES);
        NEURODIVERGENT_PROFILES.add(CognitiveProfile.HYPERFOCUS);
        NEURODIVERGENT_PROFILES.add(CognitiveProfile.SYSTEMATIZER);
        NEURODIVERGENT_PROFILES.add(CognitiveProfile.DIVERGENT);
    }

    /** All profiles. */
    private static final Set<CognitiveProfile> ALL_PROFILES =
            EnumSet.allOf(CognitiveProfile.class);

    private final Set<CognitiveProfile> enabledProfiles;

    private CognitiveProfileConfig(Set<CognitiveProfile> enabledProfiles) {
        this.enabledProfiles = Collections.unmodifiableSet(EnumSet.copyOf(enabledProfiles));
    }

    // ── Validation ──

    /**
     * Validates a requested profile against the configuration.
     * Returns the profile if enabled, or BALANCED as a safe fallback.
     *
     * <p>This is a <b>soft</b> validation — the caller gets a usable
     * profile regardless. Use {@link #requireEnabled} for strict validation.</p>
     */
    public CognitiveProfile validate(CognitiveProfile requested) {
        if (requested == null) return CognitiveProfile.BALANCED;
        return enabledProfiles.contains(requested) ? requested : CognitiveProfile.BALANCED;
    }

    /**
     * Strict validation — throws if the profile is not enabled.
     *
     * @throws IllegalArgumentException if the profile is disabled
     */
    public CognitiveProfile requireEnabled(CognitiveProfile requested) {
        if (requested == null) {
            throw new IllegalArgumentException("CognitiveProfile must not be null");
        }
        if (!enabledProfiles.contains(requested)) {
            throw new IllegalArgumentException(
                    "CognitiveProfile." + requested.name() + " is not enabled in this configuration. "
                    + "Enabled profiles: " + enabledProfiles);
        }
        return requested;
    }

    /**
     * Checks if a profile is enabled.
     */
    public boolean isEnabled(CognitiveProfile profile) {
        return enabledProfiles.contains(profile);
    }

    /**
     * Returns the set of all enabled profiles (unmodifiable).
     */
    public Set<CognitiveProfile> enabledProfiles() {
        return enabledProfiles;
    }

    // ── Presets ──

    /**
     * All profiles enabled — the default for self-hosted deployments.
     * BSL license governs commercial use, not this configuration.
     */
    public static CognitiveProfileConfig allEnabled() {
        return new CognitiveProfileConfig(ALL_PROFILES);
    }

    /**
     * Core profiles only — minimal resource footprint.
     * Suitable for embedded or resource-constrained deployments.
     */
    public static CognitiveProfileConfig coreOnly() {
        return new CognitiveProfileConfig(CORE_PROFILES);
    }

    /**
     * Core + neurodivergent profiles.
     * Suitable for research, creative, or development environments.
     */
    public static CognitiveProfileConfig withNeurodivergent() {
        return new CognitiveProfileConfig(NEURODIVERGENT_PROFILES);
    }

    /**
     * Custom configuration with specific profiles enabled.
     * BALANCED is always included as the safe fallback.
     *
     * <p>Use for SaaS tenant configuration or operational restrictions:</p>
     * <pre>{@code
     *   var config = CognitiveProfileConfig.only(
     *       CognitiveProfile.DEBUGGING,
     *       CognitiveProfile.HYPERFOCUS);
     * }</pre>
     */
    public static CognitiveProfileConfig only(CognitiveProfile... profiles) {
        EnumSet<CognitiveProfile> set = EnumSet.of(CognitiveProfile.BALANCED);
        for (CognitiveProfile p : profiles) {
            if (p != null) set.add(p);
        }
        return new CognitiveProfileConfig(set);
    }

    /**
     * Parses a configuration value from {@code spector-defaults.yml} (or overrides).
     *
     * <p>Supported values:</p>
     * <ul>
     *   <li>{@code "ALL"} — all profiles enabled (default)</li>
     *   <li>{@code "CORE_ONLY"} — core 5 profiles</li>
     *   <li>{@code "WITH_NEURODIVERGENT"} — core + neurodivergent profiles</li>
     *   <li>Comma-separated list: {@code "BALANCED,DEBUGGING,HYPERFOCUS"}</li>
     * </ul>
     *
     * <p>This is the bridge between the YAML config layer ({@code spector.memory.cognitive-profiles})
     * and the runtime config object. Called during {@code DefaultSpectorMemory} initialization.</p>
     *
     * @param value the raw config string (null or blank defaults to ALL)
     * @return the parsed config
     */
    public static CognitiveProfileConfig fromConfigValue(String value) {
        if (value == null || value.isBlank()) return allEnabled();

        return switch (value.strip().toUpperCase()) {
            case "ALL" -> allEnabled();
            case "CORE_ONLY" -> coreOnly();
            case "WITH_NEURODIVERGENT" -> withNeurodivergent();
            default -> parseProfileList(value);
        };
    }

    private static CognitiveProfileConfig parseProfileList(String csv) {
        EnumSet<CognitiveProfile> set = EnumSet.of(CognitiveProfile.BALANCED);
        for (String token : csv.split(",")) {
            String name = token.strip().toUpperCase();
            if (name.isEmpty()) continue;
            try {
                set.add(CognitiveProfile.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown cognitive profile in config: '" + token.strip()
                        + "'. Valid profiles: " + java.util.Arrays.toString(CognitiveProfile.values()));
            }
        }
        return new CognitiveProfileConfig(set);
    }

    @Override
    public String toString() {
        return "CognitiveProfileConfig{enabled=" + enabledProfiles + "}";
    }
}
