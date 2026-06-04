/**
 * Setup Wizard — Interactive CLI for first-time Spector setup.
 *
 * Invoked via `openclaw spector setup`. Walks the user through:
 * 1. Java 25+ detection / auto-download
 * 2. Embedding provider selection (Ollama, OpenAI-compatible, etc.)
 * 3. spector.jar download
 * 4. Configuration generation
 * 5. OpenClaw config update
 * 6. Health check verification
 *
 * @module setup
 */

import { existsSync, writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { homedir } from "node:os";
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";

import {
  resolveConfig,
  generateSpectorYaml,
  SpectorPaths,
  MODEL_DIMENSIONS,
  type SpectorConfig,
  type EmbeddingProvider,
} from "./config.js";
import { ensureJava } from "./jdk-manager.js";
import { ensureJar } from "./jar-manager.js";
import { SpectorBridge } from "./bridge.js";

// ─────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────

/**
 * Run the interactive setup wizard.
 *
 * This is the entry point for `openclaw spector setup`.
 */
export async function runSetup(): Promise<void> {
  const rl = createInterface({ input: stdin, output: stdout });

  try {
    printBanner();

    // ── Step 1: Java Detection ──
    console.log("\n📦 Step 1/6: Checking Java installation...\n");
    const java = await ensureJava(console.log);
    console.log(`   ✅ Java ${java.majorVersion} found (${java.source}): ${java.javaPath}\n`);

    // ── Step 2: Embedding Provider ──
    console.log("🔗 Step 2/6: Configure embedding provider\n");
    const embeddingConfig = await promptEmbeddingProvider(rl);
    console.log(`   ✅ Provider: ${embeddingConfig.provider}, Model: ${embeddingConfig.model}\n`);

    // ── Step 3: Download spector.jar ──
    console.log("⬇️  Step 3/6: Ensuring spector.jar...\n");
    const jarPath = await ensureJar(console.log);
    console.log(`   ✅ JAR ready: ${jarPath}\n`);

    // ── Step 4: Configuration ──
    console.log("⚙️  Step 4/6: Generating configuration...\n");
    const config = resolveConfig({
      javaHome: java.source === "auto-downloaded" ? dirname(dirname(java.javaPath)) : "",
      dimensions: embeddingConfig.dimensions,
      embeddingProvider: embeddingConfig.provider,
      embeddingBaseUrl: embeddingConfig.baseUrl,
      embeddingModel: embeddingConfig.model,
      embeddingApiKey: embeddingConfig.apiKey,
    });

    // Ensure directories exist
    mkdirSync(config.dataDirectory, { recursive: true });
    mkdirSync(dirname(SpectorPaths.configFile), { recursive: true });

    // Write spector.yml
    const yaml = generateSpectorYaml(config);
    writeFileSync(SpectorPaths.configFile, yaml, "utf-8");
    console.log(`   ✅ Config written: ${SpectorPaths.configFile}\n`);

    // ── Step 5: Update openclaw.json ──
    console.log("🔧 Step 5/6: Updating OpenClaw configuration...\n");
    updateOpenClawConfig(config);
    console.log("   ✅ OpenClaw config updated\n");

    // ── Step 6: Health Check ──
    console.log("🏥 Step 6/6: Verifying Spector health...\n");
    const healthy = await runHealthCheck(config);
    if (healthy) {
      console.log("   ✅ Spector is healthy and responding!\n");
    } else {
      console.log("   ⚠️  Health check skipped (Spector will start with the gateway)\n");
    }

    printSuccess();
  } finally {
    rl.close();
  }
}

// ─────────────────────────────────────────────────────────────────────
// Interactive Prompts
// ─────────────────────────────────────────────────────────────────────

interface EmbeddingSelection {
  provider: EmbeddingProvider;
  baseUrl: string;
  model: string;
  dimensions: number;
  apiKey: string;
}

async function promptEmbeddingProvider(
  rl: ReturnType<typeof createInterface>
): Promise<EmbeddingSelection> {
  console.log("   Which embedding provider would you like to use?\n");
  console.log("   [1] Ollama (local, free, recommended)");
  console.log("   [2] OpenAI API");
  console.log("   [3] OpenAI-compatible endpoint (Together, Groq, vLLM, etc.)");
  console.log("");

  const choice = await rl.question("   Choice [1]: ");
  const selected = choice.trim() || "1";

  switch (selected) {
    case "1":
      return promptOllama(rl);
    case "2":
      return promptOpenAI(rl);
    case "3":
      return promptOpenAICompatible(rl);
    default:
      console.log("   Invalid choice, defaulting to Ollama.\n");
      return promptOllama(rl);
  }
}

async function promptOllama(
  rl: ReturnType<typeof createInterface>
): Promise<EmbeddingSelection> {
  const defaultUrl = "http://localhost:11434";
  const url = await rl.question(`   Ollama URL [${defaultUrl}]: `);
  const baseUrl = url.trim() || defaultUrl;

  // Try to detect available models
  let availableModels: string[] = [];
  try {
    const response = await fetch(`${baseUrl}/api/tags`);
    if (response.ok) {
      const data = (await response.json()) as { models: Array<{ name: string }> };
      availableModels = data.models.map((m) => m.name);
      if (availableModels.length > 0) {
        console.log(`\n   Available models: ${availableModels.join(", ")}\n`);
      }
    }
  } catch {
    console.log("   ⚠️  Could not connect to Ollama. Make sure it's running.\n");
  }

  const defaultModel = "nomic-embed-text";
  const model = await rl.question(`   Embedding model [${defaultModel}]: `);
  const selectedModel = model.trim() || defaultModel;

  const defaultDims = MODEL_DIMENSIONS[selectedModel] ?? 768;
  const dimsStr = await rl.question(`   Dimensions [${defaultDims}]: `);
  const dimensions = parseInt(dimsStr.trim(), 10) || defaultDims;

  return {
    provider: "ollama",
    baseUrl,
    model: selectedModel,
    dimensions,
    apiKey: "",
  };
}

async function promptOpenAI(
  rl: ReturnType<typeof createInterface>
): Promise<EmbeddingSelection> {
  const apiKey = await rl.question("   OpenAI API key: ");
  if (!apiKey.trim()) {
    throw new Error("API key is required for OpenAI.");
  }

  const defaultModel = "text-embedding-3-small";
  const model = await rl.question(`   Model [${defaultModel}]: `);
  const selectedModel = model.trim() || defaultModel;

  const defaultDims = MODEL_DIMENSIONS[selectedModel] ?? 1536;

  return {
    provider: "openai-compatible",
    baseUrl: "https://api.openai.com",
    model: selectedModel,
    dimensions: defaultDims,
    apiKey: apiKey.trim(),
  };
}

async function promptOpenAICompatible(
  rl: ReturnType<typeof createInterface>
): Promise<EmbeddingSelection> {
  const baseUrl = await rl.question("   API base URL: ");
  if (!baseUrl.trim()) {
    throw new Error("Base URL is required.");
  }

  const apiKey = await rl.question("   API key: ");

  const model = await rl.question("   Model name: ");
  if (!model.trim()) {
    throw new Error("Model name is required.");
  }

  const dimsStr = await rl.question("   Dimensions [768]: ");
  const dimensions = parseInt(dimsStr.trim(), 10) || 768;

  return {
    provider: "openai-compatible",
    baseUrl: baseUrl.trim(),
    model: model.trim(),
    dimensions,
    apiKey: apiKey.trim(),
  };
}

// ─────────────────────────────────────────────────────────────────────
// Config Update
// ─────────────────────────────────────────────────────────────────────

/**
 * Update ~/.openclaw/openclaw.json to point the memory slot to Spector.
 */
function updateOpenClawConfig(_config: SpectorConfig): void {
  const openclawConfigPath = join(homedir(), ".openclaw", "openclaw.json");

  let existingConfig: Record<string, unknown> = {};

  if (existsSync(openclawConfigPath)) {
    try {
      const content = readFileSync(openclawConfigPath, "utf-8");
      existingConfig = JSON.parse(content) as Record<string, unknown>;
    } catch {
      // Start fresh if parse fails
    }
  }

  // Ensure plugins.slots.memory = "memory-spector"
  const plugins = (existingConfig["plugins"] as Record<string, unknown>) ?? {};
  const slots = (plugins["slots"] as Record<string, unknown>) ?? {};
  slots["memory"] = "memory-spector";
  plugins["slots"] = slots;
  existingConfig["plugins"] = plugins;

  // Ensure MCP server config for Spector
  const mcp = (existingConfig["mcp"] as Record<string, unknown>) ?? {};
  const servers = (mcp["servers"] as Record<string, unknown>) ?? {};

  // Only add if not already present
  if (!servers["spector"]) {
    servers["spector"] = {
      command: "java",
      args: [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", SpectorPaths.jarPath,
        "--config", SpectorPaths.configFile,
        "--mode", "openclaw",
      ],
    };
  }

  mcp["servers"] = servers;
  existingConfig["mcp"] = mcp;

  // Write back
  const configDir = dirname(openclawConfigPath);
  if (!existsSync(configDir)) {
    mkdirSync(configDir, { recursive: true });
  }

  writeFileSync(
    openclawConfigPath,
    JSON.stringify(existingConfig, null, 2) + "\n",
    "utf-8"
  );

  console.log(`   Updated: ${openclawConfigPath}`);
}

// ─────────────────────────────────────────────────────────────────────
// Health Check
// ─────────────────────────────────────────────────────────────────────

/**
 * Start Spector briefly and verify it responds to engine_status.
 */
async function runHealthCheck(config: SpectorConfig): Promise<boolean> {
  try {
    const bridge = new SpectorBridge(config);
    await bridge.start();

    const result = await bridge.callTool("engine_status", {}, 15_000);
    const text = result.content?.[0]?.text ?? "";
    const healthy = text.length > 0;

    await bridge.stop();
    return healthy;
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.log(`   ⚠️  Health check failed: ${message}`);
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────────
// Banner
// ─────────────────────────────────────────────────────────────────────

function printBanner(): void {
  console.log(`
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║   ⚡ Spector Cognitive Memory — Setup Wizard                 ║
║                                                              ║
║   Biologically-inspired AI memory for OpenClaw               ║
║   4-tier architecture · SIMD-accelerated · Sub-ms recall     ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
`);
}

function printSuccess(): void {
  console.log(`
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║   ✅ Setup Complete!                                         ║
║                                                              ║
║   Restart the OpenClaw gateway to activate Spector:          ║
║                                                              ║
║     openclaw gateway restart                                 ║
║                                                              ║
║   Your agent now has cognitive memory with:                  ║
║   · Cross-session persistence                                ║
║   · Emotional valence tracking                               ║
║   · Temporal decay & habituation                             ║
║   · Associative recall (Hebbian + Entity + Temporal graphs)  ║
║   · Sub-millisecond cognitive recall                         ║
║                                                              ║
║   Data stored in: ~/.openclaw/spector/                       ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
`);
}
