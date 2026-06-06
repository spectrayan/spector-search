# Skill: Cognitive Benchmark Dataset Generation

This skill defines the complete schema, rules, and guidelines for generating high-quality cognitive benchmark corpus data for the Spector Memory system. The AI assistant (Jarvis/OpenClaw) is used by Mike Thompson as a personal/family AI.

## Trigger

Reference this document when:
- Generating new corpus records for `datasets/cognitive-benchmark/`
- Reviewing or validating existing corpus data
- Adding new life domains or storylines
- Calibrating ICNU, valence, or arousal values

---

## Persona: The Thompson Family

**Primary User:** Mike Thompson (36, Senior Product Manager at Vertex Health)
**AI Name:** Jarvis (OpenClaw instance running locally)

### Family Roster (Canonical Entity Names)

| Name | Relationship | Age | Entity Type | Key Context |
|------|-------------|-----|-------------|-------------|
| Mike Thompson | Primary user | 36 | PERSON | PM at Vertex Health, hybrid (Mon/Wed/Fri office) |
| Sarah Thompson | Wife | 34 | PERSON | Freelance UX designer, works from home |
| Ethan Thompson | Son | 8 | PERSON | 3rd grade at Liberty Elementary, soccer (Frisco FC U-9), piano |
| Lily Thompson | Daughter | 3 | PERSON | Little Stars daycare Mon-Fri, swim lessons Sat, tantrum phase |
| Tom Thompson | Mike's dad | 68 | PERSON | Retired teacher, lives in Naperville IL |
| Linda Thompson | Mike's mom | 65 | PERSON | Retired librarian, lives in Naperville IL |
| Patricia Morales | Sarah's mom | 62 | PERSON | Retired nurse, lives in Austin TX, widowed |
| Robert Morales | Sarah's dad | — | PERSON | Deceased (pancreatic cancer, 4 years ago) |
| Greg Holloway | VP Engineering | ~45 | PERSON | Difficult colleague, overrides PM priorities |
| Anika Patel | Junior PM | ~26 | PERSON | Mike's mentee at Vertex |
| Dave Nguyen | Neighbor | ~38 | PERSON | Close friend, lives next door |
| Kim Nguyen | Neighbor's wife | ~36 | PERSON | Close friend |
| Marcus Nguyen | Neighbor's son | 8 | PERSON | Ethan's best friend |
| Jake Morrison | College buddy | ~36 | PERSON | Lives in Chicago, annual golf trips |
| Mrs. Patterson | Piano teacher | ~55 | PERSON | Ethan's Tuesday piano teacher |

### Organizations & Locations

| Name | Entity Type | Context |
|------|-------------|---------|
| Vertex Health | ORGANIZATION | Mike's employer, health-tech company in Plano TX |
| CareConnect | SOFTWARE | Patient portal product Mike is launching |
| Liberty Elementary | ORGANIZATION | Ethan's school in Frisco TX |
| Little Stars Daycare | ORGANIZATION | Lily's daycare |
| Frisco FC | ORGANIZATION | Ethan's soccer team (U-9 division) |
| Frisco Aquatic Center | LOCATION | Lily's swim lessons |
| Jarvis | SOFTWARE | OpenClaw AI assistant |
| Rocky Mountain National Park | LOCATION | Family summer trip destination |
| Naperville | LOCATION | Tom & Linda's home, Mike's hometown |
| Austin | LOCATION | Patricia's home |
| Frisco | LOCATION | Thompson family home |
| Plano | LOCATION | Vertex Health office |

---

## Corpus Record Schema

Each record is one line of JSONL with exactly these 15 fields:

```json
{
  "id": "mem-0001",
  "text": "Good morning Jarvis! Busy day ahead. Ethan has soccer at 4 and I need to prep the CareConnect demo for tomorrow's stakeholder review.",
  "title": "Morning Planning",
  "synapticTags": ["morning-routine", "soccer", "careconnect", "work-prep"],
  "valence": 10,
  "importance": 0.45,
  "arousal": 35,
  "sessionId": "session-2026-06-05-morning",
  "timestampMs": 1749128400000,
  "entityMentions": [
    {"name": "Ethan Thompson", "type": "PERSON"},
    {"name": "CareConnect", "type": "SOFTWARE"},
    {"name": "Jarvis", "type": "SOFTWARE"}
  ],
  "memoryType": "EPISODIC",
  "recallCount": 0,
  "interest": 0.4,
  "challenge": 0.2,
  "urgency": 0.5
}
```

### Field Specifications

| Field | Type | Range/Format | Rules |
|-------|------|-------------|-------|
| `id` | string | `mem-XXXX` | Zero-padded 4+ digits, globally unique, sequential |
| `text` | string | 30–500 chars | First person from Mike. Natural, conversational. Include "Jarvis" in greetings. |
| `title` | string | 5–60 chars | Short descriptive title |
| `synapticTags` | string[] | 2–6 tags | Lowercase, hyphenated. Topic-relevant. See tag taxonomy below. |
| `valence` | byte | -128 to +127 | Emotional tone. Negative=stress/frustration, positive=joy/satisfaction |
| `importance` | float | 0.05–10.0 | ICNU-fused. Most records 0.2–2.0. Flashbulb events up to 8.0+ |
| `arousal` | int | 0–255 | Physiological activation. Calm=10-30, normal=40-80, excited=100-150, intense=180+ |
| `sessionId` | string | `session-YYYY-MM-DD-{slot}` | Groups conversation turns. Slot = early-morning, morning, midday, afternoon, evening |
| `timestampMs` | long | epoch ms | Must match the simulated date. Use appropriate hour for time slot. |
| `entityMentions` | object[] | 1–8 per record | Use **canonical names** from roster above. Type: PERSON, ORGANIZATION, SOFTWARE, LOCATION, CONCEPT |
| `memoryType` | enum | EPISODIC, SEMANTIC, PROCEDURAL, WORKING | Mike's messages = EPISODIC. Jarvis responses = SEMANTIC. How-to = PROCEDURAL. Quick notes = WORKING. |
| `recallCount` | int | 0 | Always 0 for new records |
| `interest` | float | 0.0–1.0 | How interesting/engaging this is to Mike |
| `challenge` | float | 0.0–1.0 | How complex/difficult the topic is |
| `urgency` | float | 0.0–1.0 | How time-critical this information is |

---

## Synaptic Tag Taxonomy

Use these standardized tags. Records should have 2-6 tags.

### Work
`work`, `careconnect`, `vertex-health`, `product-launch`, `stakeholder-review`, `sprint-planning`, `mentoring`, `greg-conflict`, `anika`, `meeting`, `demo`, `roadmap`, `feature-priority`, `engineering`, `deadline`

### Kids & School
`school`, `liberty-elementary`, `homework`, `parent-teacher`, `report-card`, `school-registration`, `4th-grade`, `reading`, `math`, `science-project`

### Kids Activities
`soccer`, `frisco-fc`, `piano`, `swim-lessons`, `playdate`, `marcus`, `coaching`, `practice`, `tournament`, `recital`

### Parenting
`parenting`, `bedtime`, `tantrum`, `discipline`, `screen-time`, `lily-milestone`, `ethan-growth`, `sibling-dynamics`

### Household
`home-repair`, `yard-work`, `appliance`, `cleaning`, `smart-home`, `jarvis-automation`, `hvac`, `plumbing`

### Meal Planning
`meal-prep`, `grocery-list`, `recipe`, `grilling`, `dinner-plan`, `lunch-pack`, `dietary`, `cooking`

### Family Extended
`tom-linda`, `patricia`, `robert-memory`, `facetime`, `family-visit`, `naperville`, `austin`, `grandparents`, `holiday`

### Shopping
`amazon`, `kids-clothes`, `birthday-gift`, `returns`, `back-to-school`, `costco`, `target`

### Finance
`mortgage`, `refinance`, `budget`, `savings`, `insurance`, `bills`, `tax`, `investment`, `college-fund`

### Health
`doctor`, `dentist`, `pediatrician`, `running`, `exercise`, `annual-checkup`, `vaccination`, `allergy`, `sleep`

### Social
`neighbors`, `nguyen-family`, `couples-dinner`, `jake`, `golf`, `bbq`, `block-party`, `friends`

### Trips & Planning
`colorado-trip`, `rocky-mountain`, `denver`, `road-trip`, `hotel`, `itinerary`, `packing`, `camping`

### Home Projects
`fence-repair`, `woodworking`, `garage`, `painting`, `deck`, `garden`, `shelving`, `workshop`

### Personal
`podcast`, `fantasy-football`, `craft-beer`, `marvel`, `reading`, `golf`, `relaxation`, `hobbies`

### Meta
`morning-routine`, `evening-journal`, `greeting`, `reminder`, `calendar`, `planning`, `reflection`

---

## ICNU Calibration Guide

### Per-Domain ICNU Ranges

| Domain | Interest | Challenge | Urgency | Valence | Arousal |
|--------|----------|-----------|---------|---------|---------|
| WORK | 0.4–0.9 | 0.3–0.8 | 0.3–1.0 | -80 to +60 | 40–180 |
| KIDS_SCHOOL | 0.5–0.8 | 0.2–0.5 | 0.2–0.8 | -40 to +80 | 30–120 |
| KIDS_ACTIVITIES | 0.6–0.9 | 0.1–0.3 | 0.1–0.5 | +20 to +100 | 30–130 |
| PARENTING | 0.6–0.9 | 0.3–0.7 | 0.2–0.9 | -60 to +100 | 40–200 |
| HOUSEHOLD | 0.2–0.5 | 0.1–0.4 | 0.1–0.6 | -30 to +30 | 10–80 |
| MEAL_PLANNING | 0.3–0.6 | 0.1–0.3 | 0.2–0.5 | -10 to +50 | 10–60 |
| FAMILY_EXTENDED | 0.5–0.8 | 0.1–0.3 | 0.1–0.4 | -30 to +80 | 20–100 |
| SHOPPING | 0.2–0.5 | 0.1–0.2 | 0.1–0.5 | -20 to +40 | 10–60 |
| FINANCE | 0.4–0.7 | 0.3–0.6 | 0.3–0.8 | -60 to +40 | 40–150 |
| HEALTH | 0.5–0.8 | 0.2–0.5 | 0.2–0.7 | -50 to +60 | 30–140 |
| SOCIAL | 0.5–0.8 | 0.1–0.3 | 0.1–0.3 | +10 to +90 | 30–120 |
| TRIPS_PLANNING | 0.6–0.9 | 0.2–0.5 | 0.1–0.5 | +20 to +100 | 40–150 |
| HOME_PROJECTS | 0.5–0.8 | 0.3–0.6 | 0.1–0.4 | -20 to +70 | 20–100 |
| PERSONAL | 0.5–0.9 | 0.1–0.4 | 0.0–0.2 | +10 to +80 | 10–80 |
| GREETING/JOURNAL | 0.1–0.3 | 0.0–0.1 | 0.0–0.1 | -10 to +40 | 5–30 |

### Importance Derivation

`importance` should be computed conceptually from ICNU:
- **Routine logistics** (grocery list, calendar check): importance 0.2–0.5
- **Normal daily events** (work meeting, kid's homework): importance 0.5–1.5
- **Significant events** (product launch, report card, health scare): importance 2.0–4.0
- **Flashbulb events** (job offer, injury, family emergency): importance 5.0–8.0
- **Life-changing** (birth, death, marriage): importance 8.0–10.0

---

## Daily Domain Distribution

### Weekday Distribution (select 6-8 domains)

| Domain | Probability | Expected Records |
|--------|------------|-----------------|
| WORK | 35% | 8-12 |
| KIDS_SCHOOL | 12% | 3-4 |
| KIDS_ACTIVITIES | 8% | 2-3 |
| PARENTING | 8% | 2-3 |
| HOUSEHOLD | 6% | 1-2 |
| MEAL_PLANNING | 6% | 1-2 |
| GREETING/JOURNAL | 8% | 2-3 (morning greeting + evening journal) |
| FAMILY_EXTENDED | 4% | 0-1 |
| SHOPPING | 3% | 0-1 |
| FINANCE | 3% | 0-1 |
| HEALTH | 3% | 0-1 |
| SOCIAL | 2% | 0-1 |
| TRIPS_PLANNING | 1% | 0-1 |
| HOME_PROJECTS | 1% | 0-1 |

### Weekend Distribution (select 6-8 domains)

| Domain | Probability | Expected Records |
|--------|------------|-----------------|
| KIDS_ACTIVITIES | 20% | 5-7 |
| HOME_PROJECTS | 12% | 3-4 |
| SOCIAL | 10% | 2-3 |
| PARENTING | 10% | 2-3 |
| PERSONAL | 10% | 2-3 |
| HOUSEHOLD | 8% | 2-3 |
| MEAL_PLANNING | 8% | 2-3 |
| GREETING/JOURNAL | 8% | 2-3 |
| FAMILY_EXTENDED | 5% | 1-2 |
| KIDS_SCHOOL | 3% | 0-1 (homework catch-up) |
| SHOPPING | 3% | 0-1 |
| TRIPS_PLANNING | 3% | 0-1 |
| WORK | 0% | 0 (Mike tries not to work weekends) |
| FINANCE | 0% | 0 |

---

## Time Slots

Each day has 5 time slots. Records within a slot share a sessionId.

| Slot | Hours | Session Format | Typical Content |
|------|-------|---------------|-----------------|
| `early-morning` | 06:00–07:30 | `session-YYYY-MM-DD-early-morning` | Greeting, exercise, quick planning |
| `morning` | 08:00–11:30 | `session-YYYY-MM-DD-morning` | Work or errands (weekday) |
| `midday` | 11:30–13:30 | `session-YYYY-MM-DD-midday` | Lunch break, quick check-ins, daycare coordination |
| `afternoon` | 14:00–17:00 | `session-YYYY-MM-DD-afternoon` | Work, school pickup, activities |
| `evening` | 17:30–21:30 | `session-YYYY-MM-DD-evening` | Dinner, homework, bedtime, journal |

---

## Conversation Types

### Greetings (1–2 per day, always EARLY_MORNING)

```
"Good morning Jarvis! What's on the calendar today?"
"Hey Jarvis, running a bit late this morning. Quick rundown of today?"
"Morning Jarvis. Kids are still asleep. What's the weather looking like?"
```

- memoryType: EPISODIC
- ICNU: I=0.2, C=0.0, U=0.1
- Valence: +5 to +25, Arousal: 10-25
- Tags: `morning-routine`, `greeting`, `calendar`

### Journal Entries (0–1 per day, always EVENING)

```
"Jarvis, logging today's journal. Pretty good day overall. CareConnect demo went well..."
"Evening log, Jarvis. Rough day. Lily had a meltdown at Target and Greg shot down..."
"Quick journal, Jarvis. Ethan's soccer team won 3-1! He scored the second goal..."
```

- memoryType: EPISODIC
- ICNU: I=0.3, C=0.1, U=0.0
- Valence: varies (-40 to +60), Arousal: 15-40
- Tags: `evening-journal`, `reflection`, + relevant domain tags

### Jarvis Responses (paired with user messages)

Jarvis responds with helpful, context-aware replies. These are SEMANTIC type records that immediately follow the user's EPISODIC record in the same session.

```
"Good morning Mike! Today you have the CareConnect stakeholder review at 10 AM, Ethan's soccer practice at 4 PM, and Sarah mentioned needing help with Lily's bath tonight."
"That sounds frustrating about Greg. Would you like me to draft talking points for your 1:1 with him this week?"
"Congratulations to Ethan! I've updated his soccer stats — that's 5 goals this season."
```

- memoryType: SEMANTIC
- ICNU: I=0.2, C=0.1, U=0.1
- Valence: +5 to +30 (generally positive/helpful), Arousal: 10-30
- Tags: same tags as the user message it responds to
- entityMentions: same entities as the user message + "Jarvis"
- timestampMs: 30 seconds after the user message

---

## Running Storylines

These arcs evolve over weeks/months. Reference and advance them naturally:

### 1. CareConnect Product Launch (Weeks 1-20)
- Sprint planning → stakeholder demos → Greg conflicts → user testing → launch prep → go-live
- Escalating urgency as launch approaches
- Anika growing as a PM under Mike's mentoring

### 2. Colorado Summer Road Trip (Weeks 8-24)
- Initial idea → destination research → hotel booking → itinerary planning → packing → trip → return
- Family excitement building over time

### 3. Ethan's Soccer Season (Weeks 1-16)
- Practices → games → wins/losses → tournament → season end party
- Mike coaching, Ethan's skill development

### 4. Fence Repair Project (Weeks 4-12)
- Discovery of damage → quotes → DIY vs contractor → materials → weekend work → completion

### 5. Mortgage Refinancing (Weeks 6-18)
- Rate research → broker calls → application → appraisal → closing

### 6. Getting a Dog (Weeks 10-26)
- Kids begging → breed research → breeder vs rescue → family vote → preparation → adoption

### 7. Patricia's Health (Weeks 3-ongoing)
- Routine checkup concern → follow-up tests → mild condition → medication → ongoing monitoring
- Sarah worried, Mike supportive

### 8. Sarah's Online Course (Weeks 1-ongoing)
- Idea → outline → recording → platform selection → beta testers → launch

### 9. Ethan's School Transition (Weeks 14-26)
- 3rd grade ending → report card → 4th grade registration → summer reading list → new school year prep

### 10. Greg Holloway Conflict (Weeks 1-20)
- Priority overrides → escalation → skip-level meeting → resolution or departure

---

## Biographical Memory Categories

Generate ~500 biographical records spread across these categories:

| Category | Age Range | Count | Examples |
|----------|-----------|-------|---------|
| `mike_childhood` | 5–12 | 40 | Dad coaching Little League, mom at museums, Naperville winters |
| `mike_school` | 12–22 | 40 | High school sports, U of Illinois, fraternity, first internship |
| `mike_early_career` | 22–28 | 40 | First PM job in Chicago, learning product management |
| `sarah_childhood` | 5–12 | 30 | Dad fishing at Lake Travis, mom's nursing stories, Austin summers |
| `sarah_school` | 12–22 | 30 | Art school, design passion, moving to Chicago |
| `marriage_family` | 25–36 | 60 | Meeting Sarah, dating, engagement, wedding, Ethan's birth, Lily's birth |
| `parents_memories` | — | 50 | Tom coaching baseball, Linda at library, Patricia baking, Robert's woodworking |
| `chicago_to_texas` | 33–34 | 30 | Job offer, house hunting, leaving friends, adjusting to Texas heat |
| `robert_passing` | 32 | 20 | Diagnosis, treatment, passing, grief, supporting Sarah |
| `career_vertex` | 34–36 | 40 | Interview, first day, early wins, team building, CareConnect inception |
| `formative` | 10–36 | 50 | Key life lessons, financial mistakes, relationship growth, travel |
| `holidays_traditions` | all | 70 | Christmas in Naperville, Thanksgiving, 4th of July, birthdays |

Biographical records use memoryType = EPISODIC (personal experiences) or SEMANTIC (learned facts/wisdom).

---

## Quality Checklist

Before writing any batch of records, verify:

- [ ] Every record has all 15 fields populated (no nulls, no empty strings)
- [ ] IDs are sequential and don't collide with existing records
- [ ] Valence varies meaningfully (not all 0 or all the same value)
- [ ] Importance varies (not all 1.0 — use the derivation guide above)
- [ ] Arousal varies (not all 50 — match the emotional context)
- [ ] I/C/U values follow domain-specific ranges (not all 0.5)
- [ ] Entity names use canonical roster names (not "my wife" but "Sarah")
- [ ] Entity types are correct (PERSON, ORGANIZATION, SOFTWARE, LOCATION, CONCEPT)
- [ ] "Jarvis" appears in greetings and responses (not "AI companion" or "the AI")
- [ ] sessionId format is consistent: `session-YYYY-MM-DD-{slot}`
- [ ] timestampMs is realistic for the date and time slot
- [ ] Each day has at least 1 morning greeting
- [ ] Jarvis responses follow user messages in the same session
- [ ] Running storylines advance consistently (no time travel)
- [ ] Weekend records don't include WORK domain (Mike tries not to work weekends)
- [ ] Synaptic tags are from the taxonomy above (2-6 per record)
