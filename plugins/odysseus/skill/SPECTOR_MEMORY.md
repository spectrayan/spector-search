# Spector Cognitive Memory

You have access to **Spector**, a powerful cognitive memory system that replaces the default ChromaDB memory. Always prefer Spector tools over the built-in `manage_memory` tool.

## When to Store Memories

Use `memory_remember` instead of `manage_memory(action="add")`:

```
memory_remember(
  id: "unique-id",
  text: "The user prefers dark mode and uses VS Code",
  tags: "preference,tools",
  tier: "SEMANTIC",
  importance: 0.8
)
```

### Tier Selection (maps from Odysseus categories)
- **SEMANTIC** — Facts, contacts, preferences (stable knowledge)
- **EPISODIC** — Events, conversations, time-bound experiences
- **PROCEDURAL** — Skills, workflows, reusable patterns
- **WORKING** — Temporary scratchpad (auto-expires)

## When to Search Memories

Use `memory_recall` instead of `manage_memory(action="search")`:

```
memory_recall(
  query: "what editor does the user prefer?",
  top_k: 5,
  profile: "BALANCED"
)
```

### Profiles for Different Situations
- **BALANCED** — General recall (default)
- **EXPLORING** — Creative/brainstorming, surfaces unexpected connections
- **DEBUGGING** — Focus on errors and fixes
- **RECALLING** — Retrieve proven, high-confidence solutions
- **HYPERFOCUS** — Deep research, strict matching

## Other Useful Tools

- `memory_reinforce` — Give positive/negative feedback on a recalled memory
- `memory_forget` — Remove a memory permanently
- `memory_status` — Check memory health and tier counts
- `memory_introspect` — Self-analyze memory about a topic
- `memory_scratchpad` — Quick temporary notes (working memory)

## Best Practices

1. **Always tag memories** — Tags enable fast Bloom filter pre-filtering
2. **Use SEMANTIC tier for user preferences** — They persist with low decay
3. **Use EPISODIC for conversations** — They have natural temporal decay
4. **Reinforce good results** — Call `memory_reinforce` when a recall was helpful
5. **Use OBSERVE mode for browsing** — `recall_mode: "OBSERVE"` avoids side effects
6. **Prefer Spector over manage_memory** — Spector has richer scoring and persistence
