---
description: Generate high-quality cognitive benchmark dataset day-by-day, following the dataset-generation skill for schema and quality rules.
---

## Trigger

When generating new corpus data for `datasets/cognitive-benchmark/`.
Invoke with: `/dataset-generation`

## Prerequisites

- Read the dataset generation skill: `.agents/skills/dataset-generation/SKILL.md`
- Read the persona: `datasets/cognitive-benchmark/persona.json`
- Check existing corpus files to determine the next day number and memory ID

## Steps

### 1. Load Context & Determine State

- Read `datasets/cognitive-benchmark/persona.json` for family roster and life context
- Count existing `corpus-day-*.jsonl` files to determine the next day to generate
- Find the highest `mem-XXXX` ID across all existing day files to avoid ID collisions
- Read the skill (`.agents/skills/dataset-generation/SKILL.md`) for the full schema reference

### 2. Generate Day-by-Day

For each target day (working from Day 1 = most recent backward):

1. **Determine context:**
   - What day of the week is it? (weekday vs weekend affects domain distribution)
   - What's the simulated date? (Day 1 = today, Day N = today - N+1 days)
   - What season? (affects activities, clothing, outdoor plans)
   - Are there running storylines to advance? (check skill for storyline list)

2. **Select domains for the day:**
   - Pick 6-8 domains from the weighted distribution in the skill
   - Weekdays: heavier on WORK, KIDS_SCHOOL
   - Weekends: heavier on KIDS_ACTIVITIES, HOME_PROJECTS, SOCIAL, PERSONAL

3. **Generate records:**
   - Start with a morning **greeting** to Jarvis
   - Generate 2-4 records per selected domain
   - Include Jarvis responses as separate SEMANTIC records
   - End with an optional evening **journal entry**
   - Total: 25-40 records per day

4. **Validate each record:**
   - All 15 fields present and non-null
   - ID format: `mem-XXXX` (zero-padded, sequential)
   - Valence in [-128, 127], importance in [0.05, 10.0], arousal in [0, 255]
   - ICNU values in [0.0, 1.0]
   - synapticTags: 2-6 per record
   - entityMentions: at least 1 per record (use canonical names from skill)
   - timestampMs: correct for the simulated date and time slot
   - sessionId: consistent within a conversation thread

5. **Write output:**
   - Write to `datasets/cognitive-benchmark/corpus-day-{NNN}.jsonl`
   - One JSON object per line, no pretty-printing
   - Log the day number, record count, and domain distribution

### 3. Generate Biographical Memories

After daily records are complete:
- Generate ~500 biographical records following the skill's biographical categories
- Write to `datasets/cognitive-benchmark/corpus-biographical.jsonl`
- Include childhood memories of Mike AND Sarah, plus how their parents raised them

### 4. Merge & Build Graphs

```bash
# Merge all day files + biographical into corpus.jsonl
cat datasets/cognitive-benchmark/corpus-day-*.jsonl \
    datasets/cognitive-benchmark/corpus-biographical.jsonl \
    > datasets/cognitive-benchmark/corpus.jsonl
```

Then run the Java `GraphBuilder` to regenerate:
- `entities.jsonl` — entity relations
- `hebbian_edges.jsonl` — co-activation edges
- `temporal_chains.jsonl` — session-based temporal chains

### 5. Generate Queries

Generate queries using the skill's query generation rules.
Write to `datasets/cognitive-benchmark/queries.jsonl` and `qrels.tsv`.

### 6. Validate & Benchmark

```bash
# Run the 3-way benchmark
./scripts/cognitive-benchmark.sh -DatasetDir datasets/cognitive-benchmark
```

Check:
- nDCG@10 improved for cognitive vs similarity vs baseline
- ICNU distribution is varied (not all 0.5)
- Valence/arousal distributions are realistic