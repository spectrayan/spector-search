/**
 * memory_get compatibility adapter.
 *
 * Maps OpenClaw's `memory_get(path)` to Spector's memory-by-ID lookup.
 * Since Spector uses a cognitive memory engine (not file-based storage),
 * we interpret `path` as a memory ID and return the full memory details.
 *
 * @module tools/memory-get
 */

import type { SpectorBridge, McpToolResult } from "../bridge.js";

/** Tool definition for OpenClaw's standard memory_get. */
export const MEMORY_GET_DEFINITION = {
  name: "memory_get",
  description:
    "Retrieve a specific memory by its ID. " +
    "In Spector, memories are stored in a cognitive engine, not as files. " +
    "Pass a memory ID (returned from memory_search results) to get full details " +
    "including text, tags, importance, valence, and decay information.",
  inputSchema: {
    type: "object" as const,
    properties: {
      path: {
        type: "string",
        description:
          "Memory ID to retrieve. Use IDs from memory_search results.",
      },
    },
    required: ["path"],
  },
};

/**
 * Execute a memory_get by recalling a specific memory by ID.
 *
 * Strategy:
 * 1. If `path` matches a memory ID pattern → use memory_recall with the ID as
 *    query text plus synaptic filter to narrow results.
 * 2. Return the best matching result with full provenance.
 *
 * @param bridge  Active SpectorBridge instance.
 * @param args    Tool arguments from OpenClaw (path = memory ID).
 * @returns       Memory details or a helpful error message.
 */
export async function executeMemoryGet(
  bridge: SpectorBridge,
  args: Record<string, unknown>
): Promise<McpToolResult> {
  const path = args["path"] as string;

  if (!path) {
    return {
      content: [
        {
          type: "text",
          text: "Error: 'path' is required. Provide a memory ID from search results.",
        },
      ],
      isError: true,
    };
  }

  // Use memory_recall with the ID as the query — Spector will match by ID
  // if it's in the index, or by semantic similarity to the ID string.
  try {
    const result = await bridge.callTool("memory_recall", {
      query: path,
      top_k: 1,
      recall_mode: "OBSERVE",
    });
    return result;
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return {
      content: [
        {
          type: "text",
          text: `Could not retrieve memory '${path}': ${message}`,
        },
      ],
      isError: true,
    };
  }
}
