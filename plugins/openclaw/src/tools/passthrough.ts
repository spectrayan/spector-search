/**
 * Dynamic tool passthrough — discovers all Spector MCP tools and registers
 * them as OpenClaw tools with transparent pass-through semantics.
 *
 * This means any new tools added to the Spector MCP server are automatically
 * available in OpenClaw without plugin code changes.
 *
 * @module tools/passthrough
 */

import type { SpectorBridge, McpToolDefinition, McpToolResult } from "../bridge.js";

/**
 * Names of tools that have dedicated compatibility adapters.
 * These are NOT registered as passthrough (they have custom implementations).
 */
const ALIASED_TOOLS = new Set(["memory_search", "memory_get"]);

/**
 * Discover all available Spector tools and return them as registrable definitions.
 *
 * Filters out tools that already have dedicated adapters (memory_search, memory_get)
 * to avoid duplicate registrations.
 *
 * @param bridge  Active SpectorBridge instance.
 * @returns       Array of tool definitions ready for OpenClaw registration.
 */
export async function discoverPassthroughTools(
  bridge: SpectorBridge
): Promise<McpToolDefinition[]> {
  const allTools = await bridge.listTools();
  return allTools.filter((tool) => !ALIASED_TOOLS.has(tool.name));
}

/**
 * Execute a passthrough tool call by forwarding directly to Spector.
 *
 * @param bridge  Active SpectorBridge instance.
 * @param name    Tool name (as registered with Spector).
 * @param args    Tool arguments from OpenClaw.
 * @returns       Raw result from Spector, passed through unmodified.
 */
export async function executePassthrough(
  bridge: SpectorBridge,
  name: string,
  args: Record<string, unknown>
): Promise<McpToolResult> {
  return bridge.callTool(name, args);
}
