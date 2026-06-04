/**
 * SpectorBridge — Manages the Spector Java MCP server as a child process.
 *
 * Responsibilities:
 * - Spawns `java ... -jar spector.jar` as a long-lived subprocess
 * - Sends JSON-RPC 2.0 requests via stdin, reads responses from stdout
 * - Correlates request/response by JSON-RPC `id` field
 * - Health monitoring via periodic `engine_status` pings
 * - Auto-restart on unexpected process exit
 * - Clean shutdown on gateway stop
 *
 * All Spector logs go to stderr (not stdout), so stdout is a clean
 * JSON-RPC channel with one JSON object per line.
 *
 * @module bridge
 */

import { spawn, type ChildProcess } from "node:child_process";
import { createInterface, type Interface as ReadlineInterface } from "node:readline";
import { EventEmitter } from "node:events";
import { existsSync } from "node:fs";
import { join } from "node:path";

import { type SpectorConfig, SpectorPaths } from "./config.js";

// ─────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────

/** JSON-RPC 2.0 request envelope. */
interface JsonRpcRequest {
  readonly jsonrpc: "2.0";
  readonly id: number;
  readonly method: string;
  readonly params: Record<string, unknown>;
}

/** JSON-RPC 2.0 response envelope. */
interface JsonRpcResponse {
  readonly jsonrpc: "2.0";
  readonly id: number;
  readonly result?: unknown;
  readonly error?: {
    readonly code: number;
    readonly message: string;
    readonly data?: unknown;
  };
}

/** Pending request waiting for its response. */
interface PendingRequest {
  readonly resolve: (value: JsonRpcResponse) => void;
  readonly reject: (reason: Error) => void;
  readonly timer: ReturnType<typeof setTimeout>;
}

/** MCP tool definition returned by tools/list. */
export interface McpToolDefinition {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: Record<string, unknown>;
}

/** MCP tool call result. */
export interface McpToolResult {
  readonly content: ReadonlyArray<{
    readonly type: string;
    readonly text?: string;
  }>;
  readonly isError?: boolean;
}

/** Bridge lifecycle events. */
export interface BridgeEvents {
  ready: [];
  error: [Error];
  exit: [number | null, string | null];
  stderr: [string];
}

// ─────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────

/** Default request timeout in milliseconds. */
const REQUEST_TIMEOUT_MS = 30_000;

/** Initialization timeout (Spector needs time to load models). */
const INIT_TIMEOUT_MS = 120_000;

/** Health check interval in milliseconds. */
const HEALTH_CHECK_INTERVAL_MS = 60_000;

/** Maximum restart attempts before giving up. */
const MAX_RESTART_ATTEMPTS = 3;

/** Delay between restart attempts in milliseconds. */
const RESTART_DELAY_MS = 2_000;

// ─────────────────────────────────────────────────────────────────────
// SpectorBridge
// ─────────────────────────────────────────────────────────────────────

/**
 * Manages a Spector MCP server as a long-lived child process.
 *
 * Lifecycle:
 * 1. `start()` — spawns the Java process, sends MCP `initialize`
 * 2. `callTool(name, args)` — send a `tools/call` request and await response
 * 3. `listTools()` — discover available tools from the server
 * 4. `stop()` — graceful shutdown (close stdin → wait → SIGTERM)
 *
 * The bridge auto-restarts on unexpected process exit (up to 3 attempts).
 */
export class SpectorBridge extends EventEmitter<BridgeEvents> {
  private readonly config: SpectorConfig;
  private process: ChildProcess | null = null;
  private readline: ReadlineInterface | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, PendingRequest>();
  private healthTimer: ReturnType<typeof setInterval> | null = null;
  private restartCount = 0;
  private _initialized = false;
  private _stopping = false;

  constructor(config: SpectorConfig) {
    super();
    this.config = config;
  }

  /** Whether the bridge is initialized and ready to accept requests. */
  get initialized(): boolean {
    return this._initialized;
  }

  /**
   * Start the Spector subprocess and initialize the MCP protocol.
   *
   * @throws Error if Java/JAR not found or initialization fails.
   */
  async start(): Promise<void> {
    const javaPath = this.resolveJavaPath();
    const jarPath = this.resolveJarPath();

    if (!existsSync(jarPath)) {
      throw new Error(
        `Spector JAR not found at '${jarPath}'. Run 'openclaw spector setup' to download it.`
      );
    }

    const configFile = SpectorPaths.configFile;

    // Build the java command arguments
    const javaArgs: string[] = [];

    // JVM args from config
    const jvmParts = this.config.jvmArgs.split(/\s+/).filter(Boolean);
    javaArgs.push(...jvmParts);

    // Required JVM flags for Spector (Panama, Vector API, Preview)
    javaArgs.push(
      "--add-modules", "jdk.incubator.vector",
      "--enable-native-access=ALL-UNNAMED",
      "--enable-preview",
      "-jar", jarPath,
    );

    // Point to generated config if it exists
    if (existsSync(configFile)) {
      javaArgs.push("--config", configFile);
    }

    // OpenClaw mode
    javaArgs.push("--mode", "openclaw");

    this.process = spawn(javaPath, javaArgs, {
      stdio: ["pipe", "pipe", "pipe"],
      env: {
        ...process.env,
        // Suppress Java's console output formatting
        JAVA_TOOL_OPTIONS: "",
      },
    });

    // Drain stderr for logging (Spector logs go here)
    if (this.process.stderr) {
      const stderrRl = createInterface({ input: this.process.stderr });
      stderrRl.on("line", (line) => {
        this.emit("stderr", line);
      });
    }

    // Set up stdout readline for JSON-RPC responses
    if (this.process.stdout) {
      this.readline = createInterface({ input: this.process.stdout });
      this.readline.on("line", (line) => {
        this.handleResponse(line);
      });
    }

    // Handle process exit
    this.process.on("exit", (code, signal) => {
      this._initialized = false;
      this.rejectAllPending(
        new Error(`Spector process exited (code=${code}, signal=${signal})`)
      );
      this.emit("exit", code, signal);

      // Auto-restart on unexpected exit
      if (!this._stopping && this.restartCount < MAX_RESTART_ATTEMPTS) {
        this.restartCount++;
        setTimeout(() => {
          this.start().catch((err) => this.emit("error", err));
        }, RESTART_DELAY_MS);
      }
    });

    this.process.on("error", (err) => {
      this.emit("error", err);
    });

    // Send MCP initialize
    await this.initialize();
    this._initialized = true;
    this.restartCount = 0;

    // Start health check timer
    this.healthTimer = setInterval(() => {
      this.healthCheck().catch(() => {
        /* swallow — exit handler will restart */
      });
    }, HEALTH_CHECK_INTERVAL_MS);

    this.emit("ready");
  }

  /**
   * Call an MCP tool on the Spector server.
   *
   * @param name       Tool name (e.g., "memory_recall", "engine_status").
   * @param args       Tool arguments as a plain object.
   * @param timeoutMs  Request timeout (default 30s).
   * @returns          The tool call result.
   */
  async callTool(
    name: string,
    args: Record<string, unknown> = {},
    timeoutMs = REQUEST_TIMEOUT_MS
  ): Promise<McpToolResult> {
    const response = await this.sendRequest(
      "tools/call",
      { name, arguments: args },
      timeoutMs
    );

    if (response.error) {
      throw new Error(
        `Spector tool '${name}' failed: ${response.error.message}`
      );
    }

    return response.result as McpToolResult;
  }

  /**
   * Discover all available tools from the Spector MCP server.
   *
   * @returns Array of tool definitions with name, description, and schema.
   */
  async listTools(): Promise<McpToolDefinition[]> {
    const response = await this.sendRequest("tools/list", {});

    if (response.error) {
      throw new Error(`Failed to list tools: ${response.error.message}`);
    }

    const result = response.result as { tools: McpToolDefinition[] };
    return result.tools;
  }

  /**
   * Gracefully stop the Spector subprocess.
   *
   * Closes stdin first (signals EOF to Spector), then waits 2s before
   * sending SIGTERM if the process hasn't exited.
   */
  async stop(): Promise<void> {
    this._stopping = true;

    if (this.healthTimer) {
      clearInterval(this.healthTimer);
      this.healthTimer = null;
    }

    if (!this.process) return;

    // Close stdin to signal EOF
    this.process.stdin?.end();

    // Wait for graceful exit
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        // Force kill if not exited after 2s
        this.process?.kill("SIGTERM");
        resolve();
      }, 2_000);

      this.process?.on("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });

    this.readline?.close();
    this.readline = null;
    this.process = null;
    this._initialized = false;
  }

  // ───────────────────── Private Methods ─────────────────────

  /**
   * Send MCP `initialize` handshake.
   */
  private async initialize(): Promise<void> {
    const response = await this.sendRequest(
      "initialize",
      {
        protocolVersion: "2024-11-05",
        capabilities: {},
        clientInfo: {
          name: "openclaw-spector-plugin",
          version: "1.0.0",
        },
      },
      INIT_TIMEOUT_MS
    );

    if (response.error) {
      throw new Error(
        `MCP initialization failed: ${response.error.message}`
      );
    }
  }

  /**
   * Send a JSON-RPC request and wait for the correlated response.
   */
  private sendRequest(
    method: string,
    params: Record<string, unknown>,
    timeoutMs = REQUEST_TIMEOUT_MS
  ): Promise<JsonRpcResponse> {
    return new Promise<JsonRpcResponse>((resolve, reject) => {
      if (!this.process?.stdin?.writable) {
        reject(new Error("Spector process is not running"));
        return;
      }

      const id = this.nextId++;
      const request: JsonRpcRequest = {
        jsonrpc: "2.0",
        id,
        method,
        params,
      };

      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Request ${method} (id=${id}) timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      this.pending.set(id, { resolve, reject, timer });

      const payload = JSON.stringify(request) + "\n";
      this.process.stdin.write(payload);
    });
  }

  /**
   * Handle an incoming line from stdout (a JSON-RPC response).
   */
  private handleResponse(line: string): void {
    const trimmed = line.trim();
    if (!trimmed) return;

    let response: JsonRpcResponse;
    try {
      response = JSON.parse(trimmed) as JsonRpcResponse;
    } catch {
      // Non-JSON line on stdout — should not happen, but swallow gracefully
      return;
    }

    const pending = this.pending.get(response.id);
    if (pending) {
      clearTimeout(pending.timer);
      this.pending.delete(response.id);
      pending.resolve(response);
    }
  }

  /**
   * Health check via `engine_status` tool call.
   */
  private async healthCheck(): Promise<void> {
    await this.callTool("engine_status", {}, 10_000);
  }

  /**
   * Reject all pending requests (called on process exit).
   */
  private rejectAllPending(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }

  /**
   * Resolve the Java executable path.
   */
  private resolveJavaPath(): string {
    // 1. Explicit config
    if (this.config.javaHome) {
      const javaBin = join(this.config.javaHome, "bin", "java");
      if (existsSync(javaBin)) return javaBin;
      // Try with .exe on Windows
      const javaExe = javaBin + ".exe";
      if (existsSync(javaExe)) return javaExe;
    }

    // 2. Auto-downloaded Temurin JDK
    const temurinJava = join(SpectorPaths.jdkDir, "bin", "java");
    if (existsSync(temurinJava)) return temurinJava;
    const temurinJavaExe = temurinJava + ".exe";
    if (existsSync(temurinJavaExe)) return temurinJavaExe;

    // 3. JAVA_HOME environment variable
    const javaHome = process.env["JAVA_HOME"];
    if (javaHome) {
      const envJava = join(javaHome, "bin", "java");
      if (existsSync(envJava)) return envJava;
    }

    // 4. Fall back to system PATH
    return "java";
  }

  /**
   * Resolve the spector.jar path.
   */
  private resolveJarPath(): string {
    // 1. Explicit config
    if (this.config.spectorJarPath) {
      return this.config.spectorJarPath;
    }

    // 2. Auto-downloaded JAR
    return SpectorPaths.jarPath;
  }
}
