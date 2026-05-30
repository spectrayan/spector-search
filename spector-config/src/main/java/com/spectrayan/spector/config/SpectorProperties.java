package com.spectrayan.spector.config;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.config.error.SpectorConfigNotFoundException;

import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.MapConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.YAMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.OverrideCombiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Spring Boot-style hierarchical configuration loader for Spector Search.
 *
 * <p>Provides a single point of entry for all configuration across modules.
 * Uses Apache Commons Configuration 2 under the hood with a
 * {@link CombinedConfiguration} and {@link OverrideCombiner} to layer
 * multiple configuration sources.</p>
 *
 * <h3>Resolution Order (highest priority wins)</h3>
 * <ol>
 *   <li>Programmatic overrides (via {@link Builder#override(String, Object)})</li>
 *   <li>System properties ({@code -Dspector.engine.dimensions=768})</li>
 *   <li>Environment variables ({@code SPECTOR_ENGINE_DIMENSIONS=768})</li>
 *   <li>Profile-specific file ({@code spector-{profile}.yml})</li>
 *   <li>User config file ({@code spector.yml} in working directory)</li>
 *   <li>Classpath defaults ({@code spector-defaults.yml} in JAR)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // Auto-detect (loads from working dir + classpath defaults)
 *   SpectorProperties props = SpectorProperties.load();
 *
 *   // With profile
 *   SpectorProperties props = SpectorProperties.load("production");
 *
 *   // From explicit file
 *   SpectorProperties props = SpectorProperties.load(Path.of("/etc/spector/spector.yml"));
 *
 *   // Typed access
 *   int dims = props.getInt("spector.engine.dimensions", 384);
 *   String model = props.getString("spector.embedding.model", "nomic-embed-text");
 *   Duration timeout = props.getDuration("spector.embedding.timeout", Duration.ofSeconds(30));
 * }</pre>
 *
 * <h3>Environment Variable Mapping</h3>
 * <p>Dot-notation keys are mapped to environment variables by uppercasing and
 * replacing dots/hyphens with underscores:</p>
 * <ul>
 *   <li>{@code spector.engine.dimensions} → {@code SPECTOR_ENGINE_DIMENSIONS}</li>
 *   <li>{@code spector.hnsw.ef-construction} → {@code SPECTOR_HNSW_EF_CONSTRUCTION}</li>
 * </ul>
 */
public final class SpectorProperties {

    private static final Logger log = LoggerFactory.getLogger(SpectorProperties.class);

    /** Default config file name in working directory. */
    private static final String DEFAULT_CONFIG_FILE = "spector.yml";

    /** Default config file name on classpath (bundled in JAR). */
    private static final String CLASSPATH_DEFAULTS = "spector-defaults.yml";

    /** Profile config file pattern. */
    private static final String PROFILE_PATTERN = "spector-%s.yml";

    private final CombinedConfiguration config;

    private SpectorProperties(CombinedConfiguration config) {
        if (config == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "config"); } this.config = config;
    }

    // ─────────────── Static Factory Methods ───────────────

    /**
     * Loads configuration with auto-detection.
     * <p>Checks for a {@code spector.profile} system property or
     * {@code SPECTOR_PROFILE} environment variable to determine the active profile.</p>
     */
    public static SpectorProperties load() {
        String profile = System.getProperty("spector.profile",
                System.getenv().getOrDefault("SPECTOR_PROFILE", null));
        return new Builder().profile(profile).build();
    }

    /**
     * Loads configuration from classpath defaults only — no filesystem discovery.
     * <p>Useful for tests that should not be affected by a {@code spector.yml}
     * file in the working directory.</p>
     */
    public static SpectorProperties loadClasspathOnly() {
        return new Builder().skipFilesystemDiscovery(true).build();
    }

    /**
     * Loads configuration with the specified profile.
     *
     * @param profile the active profile name (e.g., "dev", "production"), or null for none
     */
    public static SpectorProperties load(String profile) {
        return new Builder().profile(profile).build();
    }

    /**
     * Loads configuration from an explicit file path.
     *
     * @param configFile path to the primary configuration file
     */
    public static SpectorProperties load(Path configFile) {
        return new Builder().configFile(configFile).build();
    }

    /**
     * Creates a new builder for fine-grained control over configuration loading.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────── Typed Accessors ───────────────

    /**
     * Returns the string value for the given key, or {@code null} if not found.
     */
    public String getString(String key) {
        return resolveWithEnv(key, null);
    }

    /**
     * Returns the string value for the given key, or the default if not found.
     */
    public String getString(String key, String defaultValue) {
        String value = resolveWithEnv(key, null);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns the integer value for the given key, or the default if not found.
     */
    public int getInt(String key, int defaultValue) {
        String value = resolveWithEnv(key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid integer for key '{}': '{}', using default {}", key, value, defaultValue);
            }
        }
        return config.getInt(key, defaultValue);
    }

    /**
     * Returns the long value for the given key, or the default if not found.
     */
    public long getLong(String key, long defaultValue) {
        String value = resolveWithEnv(key, null);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid long for key '{}': '{}', using default {}", key, value, defaultValue);
            }
        }
        return config.getLong(key, defaultValue);
    }

    /**
     * Returns the boolean value for the given key, or the default if not found.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = resolveWithEnv(key, null);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return config.getBoolean(key, defaultValue);
    }

    /**
     * Returns the double value for the given key, or the default if not found.
     */
    public double getDouble(String key, double defaultValue) {
        String value = resolveWithEnv(key, null);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid double for key '{}': '{}', using default {}", key, value, defaultValue);
            }
        }
        return config.getDouble(key, defaultValue);
    }

    /**
     * Returns the float value for the given key, or the default if not found.
     */
    public float getFloat(String key, float defaultValue) {
        String value = resolveWithEnv(key, null);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid float for key '{}': '{}', using default {}", key, value, defaultValue);
            }
        }
        return config.getFloat(key, defaultValue);
    }

    /**
     * Returns a {@link Duration} parsed from the given key.
     * <p>Supports formats: {@code 30s}, {@code 5m}, {@code 1h}, {@code 500ms},
     * or ISO-8601 ({@code PT30S}).</p>
     */
    public Duration getDuration(String key, Duration defaultValue) {
        String value = getString(key);
        if (value == null || value.isBlank()) return defaultValue;
        return parseDuration(value, key, defaultValue);
    }

    /**
     * Returns a {@link Path} for the given key, or the default if not found.
     */
    public Path getPath(String key, Path defaultValue) {
        String value = getString(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Path.of(value);
    }

    /**
     * Returns an enum value for the given key, or the default if not found.
     */
    public <E extends Enum<E>> E getEnum(String key, Class<E> enumType, E defaultValue) {
        String value = getString(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Enum.valueOf(enumType, value.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid {} value for key '{}': '{}', using default {}",
                    enumType.getSimpleName(), key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns a view of this configuration scoped to the given prefix.
     * <p>Example: {@code subset("spector.embedding")} returns a view where
     * the key {@code "model"} maps to {@code "spector.embedding.model"}.</p>
     */
    public SpectorProperties subset(String prefix) {
        CombinedConfiguration sub = new CombinedConfiguration(new OverrideCombiner());
        sub.addConfiguration(config.subset(prefix));
        return new SpectorProperties(sub);
    }

    /**
     * Checks if the configuration contains the given key.
     */
    public boolean containsKey(String key) {
        return resolveWithEnv(key, null) != null || config.containsKey(key);
    }

    /**
     * Returns all keys in the configuration.
     */
    public Iterator<String> getKeys() {
        return config.getKeys();
    }

    // ─────────────── Environment Variable Resolution ───────────────

    /**
     * Resolves a key by first checking system properties, then environment
     * variables (with dot-to-underscore mapping), then the configuration.
     */
    private String resolveWithEnv(String key, String defaultValue) {
        // 1. System property
        String sysProp = System.getProperty(key);
        if (sysProp != null) return sysProp;

        // 2. Environment variable (spector.engine.dimensions → SPECTOR_ENGINE_DIMENSIONS)
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null) return envValue;

        // 3. Configuration file
        String configValue = config.getString(key, null);
        return configValue != null ? configValue : defaultValue;
    }

    // ─────────────── Duration Parsing ───────────────

    private static Duration parseDuration(String value, String key, Duration defaultValue) {
        try {
            // Try ISO-8601 first (PT30S, PT5M, etc.)
            if (value.startsWith("PT") || value.startsWith("pt")) {
                return Duration.parse(value);
            }

            // Human-readable: 30s, 5m, 1h, 500ms
            String trimmed = value.trim().toLowerCase();
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()));
            } else if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            }

            // Try as seconds if just a number
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (Exception e) {
            log.warn("Invalid duration for key '{}': '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    // ─────────────── Builder ───────────────

    /**
     * Builder for fine-grained control over {@link SpectorProperties} construction.
     */
    public static class Builder {
        private String profile;
        private Path configFile;
        private boolean skipFilesystem;
        private final Properties overrides = new Properties();

        private Builder() {}

        /** Sets the active profile (e.g., "dev", "production"). */
        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        /** Sets an explicit configuration file path. */
        public Builder configFile(Path configFile) {
            this.configFile = configFile;
            return this;
        }

        /** If true, skip loading from working-directory files (spector.yml, spector.properties). */
        Builder skipFilesystemDiscovery(boolean skip) {
            this.skipFilesystem = skip;
            return this;
        }

        /** Adds a programmatic override. */
        public Builder override(String key, Object value) {
            overrides.setProperty(key, String.valueOf(value));
            return this;
        }

        /** Adds multiple programmatic overrides. */
        public Builder overrides(Map<String, ?> overrides) {
            overrides.forEach((k, v) -> this.overrides.setProperty(k, String.valueOf(v)));
            return this;
        }

        /**
         * Builds the {@link SpectorProperties} instance.
         *
         * <p>Layer order (first added = highest priority with OverrideCombiner):</p>
         * <ol>
         *   <li>Programmatic overrides</li>
         *   <li>Profile-specific YAML (if profile set)</li>
         *   <li>User config file (explicit path or spector.yml in working dir)</li>
         *   <li>Classpath defaults (spector-defaults.yml)</li>
         * </ol>
         */
        public SpectorProperties build() {
            CombinedConfiguration combined = new CombinedConfiguration(new OverrideCombiner());

            // 1. Programmatic overrides (highest priority)
            if (!overrides.isEmpty()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                MapConfiguration overrideConfig = new MapConfiguration((Map) overrides);
                combined.addConfiguration(overrideConfig, "overrides");
                log.debug("[SpectorProperties] Added {} programmatic overrides", overrides.size());
            }

            // 2. Profile-specific file
            if (profile != null && !profile.isBlank()) {
                String profileFileName = String.format(PROFILE_PATTERN, profile);
                loadFileIfExists(combined, Path.of(profileFileName), "profile-" + profile);
                loadClasspathYaml(combined, profileFileName, "classpath-profile-" + profile);
            }

            // 3. User config file
            if (configFile != null) {
                loadFileOrFail(combined, configFile, "user-config");
            } else if (!skipFilesystem) {
                // Try spector.yml in working directory
                loadFileIfExists(combined, Path.of(DEFAULT_CONFIG_FILE), "user-config");
                // Also try spector.properties as fallback
                loadPropertiesIfExists(combined, Path.of("spector.properties"), "user-properties");
            }

            // 4. Classpath defaults (lowest priority)
            loadClasspathYaml(combined, CLASSPATH_DEFAULTS, "classpath-defaults");

            log.info("[SpectorProperties] Loaded {} configuration sources{}",
                    combined.getNumberOfConfigurations(),
                    profile != null ? " (profile: " + profile + ")" : "");

            return new SpectorProperties(combined);
        }

        // ─── File Loading Helpers ───

        private void loadFileIfExists(CombinedConfiguration combined, Path path, String name) {
            if (Files.isRegularFile(path)) {
                try {
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                        YAMLConfiguration yaml = new YAMLConfiguration();
                        try (Reader reader = Files.newBufferedReader(path)) {
                            yaml.read(reader);
                        }
                        combined.addConfiguration(yaml, name);
                        log.debug("[SpectorProperties] Loaded YAML: {}", path.toAbsolutePath());
                    } else if (fileName.endsWith(".properties")) {
                        PropertiesConfiguration props = new PropertiesConfiguration();
                        try (Reader reader = Files.newBufferedReader(path)) {
                            props.read(reader);
                        }
                        combined.addConfiguration(props, name);
                        log.debug("[SpectorProperties] Loaded properties: {}", path.toAbsolutePath());
                    }
                } catch (ConfigurationException | IOException e) {
                    log.warn("[SpectorProperties] Failed to load {}: {}", path, e.getMessage());
                }
            }
        }

        private void loadFileOrFail(CombinedConfiguration combined, Path path, String name) {
            if (!Files.isRegularFile(path)) {
                throw new SpectorConfigNotFoundException(path.toAbsolutePath().toString());
            }
            loadFileIfExists(combined, path, name);
        }

        private void loadPropertiesIfExists(CombinedConfiguration combined, Path path, String name) {
            if (Files.isRegularFile(path)) {
                try {
                    PropertiesConfiguration props = new PropertiesConfiguration();
                    try (Reader reader = Files.newBufferedReader(path)) {
                        props.read(reader);
                    }
                    combined.addConfiguration(props, name);
                    log.debug("[SpectorProperties] Loaded properties: {}", path.toAbsolutePath());
                } catch (ConfigurationException | IOException e) {
                    log.warn("[SpectorProperties] Failed to load {}: {}", path, e.getMessage());
                }
            }
        }

        private void loadClasspathYaml(CombinedConfiguration combined, String resource, String name) {
            try (InputStream is = SpectorProperties.class.getClassLoader().getResourceAsStream(resource)) {
                if (is != null) {
                    YAMLConfiguration yaml = new YAMLConfiguration();
                    yaml.read(new java.io.InputStreamReader(is));
                    combined.addConfiguration(yaml, name);
                    log.debug("[SpectorProperties] Loaded classpath: {}", resource);
                }
            } catch (ConfigurationException | IOException e) {
                log.warn("[SpectorProperties] Failed to load classpath {}: {}", resource, e.getMessage());
            }
        }
    }
}
