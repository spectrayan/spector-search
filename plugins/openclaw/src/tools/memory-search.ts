/**
 * memory_search compatibility adapter.
 *
 * Maps OpenClaw's standard `memory_search(query)` contract to Spector's
 * richer `memory_recall` tool. Uses OBSERVE mode (no side effects) since
 * OpenClaw treats memory_search as a read-only operation.
 *
 * @module tools/memory-search
 */

import type { SpectorBridge, McpToolResult } from "../bridge.js";

/** Tool definition for OpenClaw's standard memory_search. */
export const MEMORY_SEARCH_DEFINITION = {
  name: "memory_search",
  description:
    "Search your long-term memory for relevant information. " +
    "Uses Spector's cognitive recall with fused scoring across all memory tiers " +
    "(Working, Episodic, Semantic, Procedural). " +
    "Results are ranked by a combination of semantic similarity, importance, " +
    "temporal decay, and emotional valence.",
  inputSchema: {
    type: "object" as const,
    properties: {
      query: {
        type: "string",
        description: "Natural language search query.",
      },
      count: {
        type: "number",
        description: "Maximum number of results to return (default: 10).",
      },
    },
    required: ["query"],
  },
};

/**
 * Execute a memory_search request by delegating to Spector's memory_recall.
 *
 * @param bridge  Active SpectorBridge instance.
 * @param args    Tool arguments from OpenClaw (query, optional count).
 * @returns       Formatted search results.
 */
export async function executeMemorySearch(
  bridge: SpectorBridge,
  args: Record<string, unknown>
): Promise<McpToolResult> {
  const query = args["query"] as string;
  const count = (args["count"] as number) ?? 10;

  // Map to Spector's memory_recall with OBSERVE mode (pure read, no side effects)
  return bridge.callTool("memory_recall", {
    query,
    top_k: count,
    recall_mode: "OBSERVE",
  });
}
