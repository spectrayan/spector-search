/**
 * Spector OpenClaw Plugin — Configuration Schema & Validation.
 *
 * Defines the typed configuration interface and validation logic for the
 * Spector memory plugin. Supports multiple embedding providers (Ollama,
 * OpenAI-compatible) and auto-detection of Java/JAR paths.
 *
 * @module config
 */

import { homedir } from "node:os";
import { join } from "node:path";

// ─────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────

/** Supported embedding provider types. */
export type EmbeddingProvider = "ollama" | "openai-compatible";

/** Tag extraction strategies. */
export type TagExtractor = "content" | "llm" | "none";

/** Log levels for the Spector JVM process. */
export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

/** Full typed configuration for the Spector plugin. */
export interface SpectorConfig {
  /** Path to spector.jar. Empty = auto-download. */
  readonly spectorJarPath: string;

  /** Path to Java 25+ home. Empty = auto-download Temurin. */
  readonly javaHome: string;

  /** Vector embedding dimensions. */
  readonly dimensions: number;

  /** Embedding provider type. */
  readonly embeddingProvider: EmbeddingProvider;

  /** Base URL for embedding requests. */
  readonly embeddingBaseUrl: string;

  /** Embedding model name. */
  readonly embeddingModel: string;

  /** API key for embedding provider (optional for Ollama). */
  readonly embeddingApiKey: string;

  /** Persistent data directory. */
  readonly dataDirectory: string;

  /** Maximum memory capacity. */
  readonly capacity: number;

  /** Extra JVM arguments. */
  readonly jvmArgs: string;

  /** Tag extraction strategy. */
  readonly tagExtractor: TagExtractor;

  /** Spector server log level. */
  readonly logLevel: LogLevel;
}

// ─────────────────────────────────────────────────────────────────────
// Defaults
// ─────────────────────────────────────────────────────────────────────

/** Default data root under the user's home directory. */
const SPECTOR_HOME = join(homedir(), ".openclaw", "spector");

/** Default configuration values. */
export const DEFAULTS: SpectorConfig = {
  spectorJarPath: "",
  javaHome: "",
  dimensions: 768,
  embeddingProvider: "ollama",
  embeddingBaseUrl: "http://localhost:11434",
  embeddingModel: "nomic-embed-text",
  embeddingApiKey: "",
  dataDirectory: join(SPECTOR_HOME, "data"),
  capacity: 100_000,
  jvmArgs: "-Xms256m -Xmx1g",
  tagExtractor: "content",
  logLevel: "INFO",
};

/** Well-known model → dimension mappings. */
export const MODEL_DIMENSIONS: Record<string, number> = {
  "nomic-embed-text": 768,
  "qwen3-embedding": 4096,
  "qwen3-embedding:latest": 4096,
  "text-embedding-3-small": 1536,
  "text-embedding-3-large": 3072,
  "text-embedding-ada-002": 1536,
  "mxbai-embed-large": 1024,
  "all-minilm": 384,
  "snowflake-arctic-embed": 1024,
};

/**
 * Paths derived from configuration.
 */
export const SpectorPaths = {
  /** Root directory for all Spector data under OpenClaw. */
  home: SPECTOR_HOME,

  /** Directory for auto-downloaded JAR files. */
  binDir: join(SPECTOR_HOME, "bin"),

  /** Directory for auto-downloaded JDK. */
  jdkDir: join(SPECTOR_HOME, "jdk"),

  /** Path to the auto-downloaded spector.jar. */
  jarPath: join(SPECTOR_HOME, "bin", "spector.jar"),

  /** Path to version tracking file. */
  versionFile: join(SPECTOR_HOME, "bin", "version.json"),

  /** Path to generated spector.yml config. */
  configFile: join(SPECTOR_HOME, "spector.yml"),
} as const;

// ─────────────────────────────────────────────────────────────────────
// Validation
// ─────────────────────────────────────────────────────────────────────

/** Validation error with field name and message. */
export interface ConfigError {
  readonly field: string;
  readonly message: string;
}

/**
 * Merge partial user config with defaults, producing a fully resolved config.
 *
 * @param partial  Raw config from OpenClaw's plugin settings (may be sparse).
 * @returns        Fully resolved configuration with defaults applied.
 */
export function resolveConfig(partial: Partial<SpectorConfig>): SpectorConfig {
  return {
    spectorJarPath: partial.spectorJarPath ?? DEFAULTS.spectorJarPath,
    javaHome: partial.javaHome ?? DEFAULTS.javaHome,
    dimensions: partial.dimensions ?? DEFAULTS.dimensions,
    embeddingProvider: partial.embeddingProvider ?? DEFAULTS.embeddingProvider,
    embeddingBaseUrl: partial.embeddingBaseUrl ?? DEFAULTS.embeddingBaseUrl,
    embeddingModel: partial.embeddingModel ?? DEFAULTS.embeddingModel,
    embeddingApiKey: partial.embeddingApiKey ?? DEFAULTS.embeddingApiKey,
    dataDirectory: partial.dataDirectory ?? DEFAULTS.dataDirectory,
    capacity: partial.capacity ?? DEFAULTS.capacity,
    jvmArgs: partial.jvmArgs ?? DEFAULTS.jvmArgs,
    tagExtractor: partial.tagExtractor ?? DEFAULTS.tagExtractor,
    logLevel: partial.logLevel ?? DEFAULTS.logLevel,
  };
}

/**
 * Validate a resolved configuration.
 *
 * @param config  The resolved config to validate.
 * @returns       Array of validation errors (empty if valid).
 */
export function validateConfig(config: SpectorConfig): ConfigError[] {
  const errors: ConfigError[] = [];

  if (config.dimensions < 1 || config.dimensions > 8192) {
    errors.push({
      field: "dimensions",
      message: `Dimensions must be between 1 and 8192, got ${config.dimensions}`,
    });
  }

  if (config.capacity < 100 || config.capacity > 10_000_000) {
    errors.push({
      field: "capacity",
      message: `Capacity must be between 100 and 10,000,000, got ${config.capacity}`,
    });
  }

  if (
    config.embeddingProvider === "openai-compatible" &&
    !config.embeddingApiKey
  ) {
    errors.push({
      field: "embeddingApiKey",
      message:
        "API key is required for OpenAI-compatible embedding providers.",
    });
  }

  if (!config.embeddingBaseUrl) {
    errors.push({
      field: "embeddingBaseUrl",
      message: "Embedding base URL is required.",
    });
  }

  if (!config.embeddingModel) {
    errors.push({
      field: "embeddingModel",
      message: "Embedding model name is required.",
    });
  }

  const validProviders: EmbeddingProvider[] = ["ollama", "openai-compatible"];
  if (!validProviders.includes(config.embeddingProvider)) {
    errors.push({
      field: "embeddingProvider",
      message: `Invalid embedding provider '${config.embeddingProvider}'. Must be one of: ${validProviders.join(", ")}`,
    });
  }

  const validExtractors: TagExtractor[] = ["content", "llm", "none"];
  if (!validExtractors.includes(config.tagExtractor)) {
    errors.push({
      field: "tagExtractor",
      message: `Invalid tag extractor '${config.tagExtractor}'. Must be one of: ${validExtractors.join(", ")}`,
    });
  }

  return errors;
}

/**
 * Generate a `spector.yml` configuration file content from resolved config.
 *
 * @param config  Resolved plugin configuration.
 * @returns       YAML string for the Spector MCP server.
 */
export function generateSpectorYaml(config: SpectorConfig): string {
  const lines: string[] = [
    "# ═══════════════════════════════════════════════════════════════",
    "# Spector — OpenClaw Memory Backend Configuration",
    "# Generated by @spectrayan/spector plugin. Do not edit manually.",
    "# ═══════════════════════════════════════════════════════════════",
    "",
    "spector:",
    "  mode: memory",
    "",
    "  engine:",
    `    dimensions: ${config.dimensions}`,
    `    capacity: ${config.capacity}`,
    "    similarity: COSINE",
    "    index-type: HNSW",
    "    persistence-mode: DISK",
    `    data-directory: ${config.dataDirectory}/index`,
    "",
    "  embedding:",
    `    model: ${config.embeddingModel}`,
    `    base-url: ${config.embeddingBaseUrl}`,
    "    timeout: 120s",
    "    batch-size: 32",
  ];

  // Add API key for OpenAI-compatible providers
  if (config.embeddingProvider === "openai-compatible" && config.embeddingApiKey) {
    lines.push(`    api-key: ${config.embeddingApiKey}`);
  }

  lines.push(
    "",
    "  memory:",
    "    enabled: true",
    "    persistence-mode: DISK",
    `    persistence-path: ${config.dataDirectory}/memory`,
    `    dimensions: ${config.dimensions}`,
    `    capacity: ${config.capacity}`,
    "    decay-enabled: true",
    "    consolidation-interval: 60s",
    "    default-ingestion-tier: SEMANTIC",
    `    tag-extractor: ${config.tagExtractor}`,
    "",
    "  hnsw:",
    "    m: 16",
    "    ef-construction: 200",
    "    ef-search: 50",
  );

  return lines.join("\n") + "\n";
}
