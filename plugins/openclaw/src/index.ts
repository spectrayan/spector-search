/**
 * @spectrayan/spector — OpenClaw Plugin Entry Point.
 *
 * Registers Spector Cognitive Memory as an OpenClaw memory plugin.
 * This file is the single extension entry point declared in package.json
 * under `openclaw.extensions`.
 *
 * On registration, it:
 * 1. Reads plugin config from OpenClaw's settings
 * 2. Starts the SpectorBridge (Java subprocess via MCP stdio)
 * 3. Registers compatibility aliases (memory_search, memory_get)
 * 4. Dynamically discovers and registers all Spector-native tools
 * 5. Registers OpenClaw skills for cognitive memory features
 *
 * @module index
 */

import { SpectorBridge } from "./bridge.js";
import { resolveConfig, validateConfig, generateSpectorYaml, SpectorPaths } from "./config.js";
import {
  MEMORY_SEARCH_DEFINITION,
  executeMemorySearch,
} from "./tools/memory-search.js";
import {
  MEMORY_GET_DEFINITION,
  executeMemoryGet,
} from "./tools/memory-get.js";
import {
  discoverPassthroughTools,
  executePassthrough,
} from "./tools/passthrough.js";
import { writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname } from "node:path";

// ─────────────────────────────────────────────────────────────────────
// Plugin SDK types (these would come from openclaw/plugin-sdk)
// ─────────────────────────────────────────────────────────────────────

/** OpenClaw plugin API provided to register(). */
interface PluginApi {
  /** Current registration mode (full, discovery, cli-metadata). */
  readonly registrationMode: "full" | "discovery" | "cli-metadata";

  /** Register a tool with the OpenClaw agent. */
  registerTool(definition: ToolRegistration): void;

  /** Register a skill (agent instruction set). */
  registerSkill(skill: SkillRegistration): void;

  /** Get plugin configuration (from openclaw.json). */
  getConfig<T>(): T;

  /** Log messages visible in OpenClaw's debug output. */
  log: {
    info(message: string): void;
    warn(message: string): void;
    error(message: string): void;
    debug(message: string): void;
  };

  /** Register a shutdown handler. */
  onShutdown(handler: () => Promise<void> | void): void;
}

/** Tool registration for OpenClaw. */
interface ToolRegistration {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (args: Record<string, unknown>) => Promise<unknown>;
}

/** Skill registration for OpenClaw. */
interface SkillRegistration {
  name: string;
  description: string;
  instructions: string;
}

/** Plugin entry definition. */
interface PluginEntry {
  id: string;
  name: string;
  register: (api: PluginApi) => Promise<void> | void;
}

// ─────────────────────────────────────────────────────────────────────
// Plugin Definition
// ─────────────────────────────────────────────────────────────────────

/**
 * definePluginEntry — exported default for OpenClaw's loader.
 */
const plugin: PluginEntry = {
  id: "memory-spector",
  name: "Spector Cognitive Memory",

  async register(api: PluginApi) {
    // Skip full initialization in discovery/metadata modes
    if (api.registrationMode !== "full") {
      api.log.debug("[Spector] Skipping full init (mode: " + api.registrationMode + ")");
      return;
    }

    api.log.info("[Spector] ⚡ Initializing Spector Cognitive Memory...");

    // ── 1. Load and validate configuration ──
    const rawConfig = api.getConfig<Record<string, unknown>>();
    const config = resolveConfig(rawConfig);
    const errors = validateConfig(config);

    if (errors.length > 0) {
      for (const err of errors) {
        api.log.error(`[Spector] Config error: ${err.field} — ${err.message}`);
      }
      throw new Error(
        `Spector configuration invalid: ${errors.map((e) => e.message).join("; ")}`
      );
    }

    // ── 2. Ensure config file exists ──
    ensureSpectorConfig(config, api);

    // ── 3. Start the SpectorBridge ──
    const bridge = new SpectorBridge(config);

    bridge.on("stderr", (line) => {
      api.log.debug(`[Spector JVM] ${line}`);
    });

    bridge.on("error", (err) => {
      api.log.error(`[Spector] Bridge error: ${err.message}`);
    });

    bridge.on("exit", (code, signal) => {
      api.log.warn(
        `[Spector] Process exited (code=${code}, signal=${signal})`
      );
    });

    bridge.on("ready", () => {
      api.log.info("[Spector] ✅ Bridge ready — cognitive memory online");
    });

    try {
      await bridge.start();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      api.log.error(`[Spector] Failed to start: ${message}`);
      throw err;
    }

    // ── 4. Register compatibility aliases ──
    api.registerTool({
      ...MEMORY_SEARCH_DEFINITION,
      handler: (args) => executeMemorySearch(bridge, args),
    });

    api.registerTool({
      ...MEMORY_GET_DEFINITION,
      handler: (args) => executeMemoryGet(bridge, args),
    });

    api.log.info("[Spector] Registered compatibility tools: memory_search, memory_get");

    // ── 5. Discover and register all Spector-native tools ──
    try {
      const nativeTools = await discoverPassthroughTools(bridge);
      for (const tool of nativeTools) {
        api.registerTool({
          name: tool.name,
          description: tool.description,
          inputSchema: tool.inputSchema,
          handler: (args) => executePassthrough(bridge, tool.name, args),
        });
      }
      api.log.info(
        `[Spector] Registered ${nativeTools.length} native tools: ${nativeTools.map((t) => t.name).join(", ")}`
      );
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      api.log.warn(`[Spector] Could not discover native tools: ${message}`);
    }

    // ── 6. Register OpenClaw skills ──
    registerSkills(api);

    // ── 7. Shutdown handler ──
    api.onShutdown(async () => {
      api.log.info("[Spector] Shutting down...");
      await bridge.stop();
      api.log.info("[Spector] Shutdown complete");
    });

    api.log.info("[Spector] ⚡ Cognitive memory fully initialized");
  },
};

// ─────────────────────────────────────────────────────────────────────
// Skills
// ─────────────────────────────────────────────────────────────────────

/**
 * Register OpenClaw skills that teach the agent how to use
 * Spector's cognitive memory features effectively.
 */
function registerSkills(api: PluginApi): void {
  api.registerSkill({
    name: "spector-cognitive-memory",
    description: "How to use Spector's cognitive memory system effectively",
    instructions: `
# Spector Cognitive Memory — Agent Guide

You have access to a powerful cognitive memory system called Spector. Unlike simple key-value stores, Spector models memory like a biological brain with 4 tiers, emotional context, and associative recall.

## Memory Tiers
When storing memories with \`memory_remember\`, choose the right tier:
- **WORKING**: Ephemeral scratchpad — disappears after session (use for temporary notes)
- **EPISODIC**: Personal experiences with time context (conversations, events, user interactions)
- **SEMANTIC**: Facts and knowledge (user preferences, world knowledge, learned concepts)
- **PROCEDURAL**: Skills and patterns (how-to procedures, workflows, coding patterns)

## Cognitive Profiles
When recalling with \`memory_recall\`, use profiles to tune scoring:
- **BALANCED**: General-purpose (default)
- **EXPLORING**: Creative/associative — surfaces unexpected connections
- **DEBUGGING**: Focuses on errors, failures, and fixes
- **RECALLING**: Surfaces proven, high-confidence solutions
- **HYPERFOCUS**: Narrow deep-dive — strict tag matching, zero decay
- **DIVERGENT**: Cross-domain lateral thinking — for brainstorming
- **THE_EXECUTOR**: Strict task-oriented — no tangents
- **PARANOID_SENTINEL**: Threat/security focused recall

## Best Practices
1. **Tag everything**: Use \`tags\` when storing memories — they enable Bloom filter pre-filtering for faster recall
2. **Reinforce good results**: Call \`memory_reinforce\` with positive feedback when a recalled memory was helpful
3. **Use emotional context**: Set \`valence\` for emotionally significant memories (-128 to +127)
4. **Store interactions as EPISODIC**: User messages and conversation outcomes go in episodic memory
5. **Store preferences as SEMANTIC**: User preferences, facts, and stable knowledge go in semantic memory
6. **Use OBSERVE mode for searches**: When just browsing, use \`recall_mode: "OBSERVE"\` to avoid mutating memory state
7. **Check memory_status periodically**: Monitor tier counts and health
8. **Use memory_introspect for reflection**: Ask Spector to analyze its own memory about a topic
`,
  });

  api.registerSkill({
    name: "spector-cross-channel-memory",
    description: "How to use memory across OpenClaw channels",
    instructions: `
# Cross-Channel Memory with Spector

Spector maintains a single unified memory across all OpenClaw channels (WhatsApp, Telegram, Slack, Discord). This means:

1. **Memories persist across channels**: Something learned in WhatsApp is available in Telegram
2. **Use channel tags**: When storing, tag with the source channel for context:
   - \`memory_remember(id: "...", text: "...", tags: "whatsapp,user-pref")\`
3. **Filter by channel when needed**: Use \`synaptic_filter\` to scope recall to a specific channel
4. **User context is unified**: Preferences learned in one channel apply everywhere

## Privacy
Both OpenClaw and Spector run locally — memories never leave the user's machine.
`,
  });

  api.log.info("[Spector] Registered 2 cognitive memory skills");
}

// ─────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────

/**
 * Ensure the spector.yml config file exists, creating it if needed.
 */
function ensureSpectorConfig(
  config: ReturnType<typeof resolveConfig>,
  api: PluginApi
): void {
  const configPath = SpectorPaths.configFile;

  // Ensure directories exist
  const configDir = dirname(configPath);
  if (!existsSync(configDir)) {
    mkdirSync(configDir, { recursive: true });
  }

  const dataDir = config.dataDirectory;
  if (!existsSync(dataDir)) {
    mkdirSync(dataDir, { recursive: true });
  }

  // Generate config file if it doesn't exist
  if (!existsSync(configPath)) {
    const yaml = generateSpectorYaml(config);
    writeFileSync(configPath, yaml, "utf-8");
    api.log.info(`[Spector] Generated config: ${configPath}`);
  }
}

export default plugin;
