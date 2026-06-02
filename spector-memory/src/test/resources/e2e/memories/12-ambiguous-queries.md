# Ambiguous Query Memories
# Memories with overloaded terms, homonyms, and context-dependent meaning
# Used to test semantic disambiguation quality

# ── "Pool" Homonym Cluster ──

---
id: ambig-001
type: EPISODIC
source: OBSERVED
tags: ambiguous, pool, recreation
valence: 5
---
The office swimming pool on the rooftop was closed for maintenance after the filtration system failed. Chlorine levels exceeded safe limits. The facilities team estimates a 2-week repair timeline. Meanwhile, the gym next door offers discounted pool access.

---
id: ambig-002
type: EPISODIC
source: OBSERVED
tags: ambiguous, pool, genetics
valence: 10
---
Gene pool diversity analysis using bioinformatics pipeline showed that the sample population had unusually low heterozygosity. The Hardy-Weinberg equilibrium test flagged 3 loci as significantly deviated, suggesting selection pressure or population bottleneck.

---
id: ambig-003
type: EPISODIC
source: OBSERVED
tags: ambiguous, pool, business
valence: 0
---
The talent pool for senior Rust developers is extremely limited in our market. Recruiting pipeline shows only 12 qualified candidates in the last quarter. Considering offering remote positions to expand the candidate pool globally.

# ── "Migration" Overloaded Term ──

---
id: ambig-004
type: EPISODIC
source: OBSERVED
tags: ambiguous, migration, nature, biology
valence: 0
---
The annual monarch butterfly migration tracking app uses GPS tags weighing 0.3 grams. This year's migration route shifted 200km east due to unusual wind patterns. The data feeds into a citizen science project with 50,000 volunteer observers.

---
id: ambig-005
type: EPISODIC
source: OBSERVED
tags: ambiguous, migration, cloud
valence: -10
---
Cloud migration of the legacy ERP system failed its first attempt. The COBOL batch jobs couldn't be containerized without rewriting the file-based IPC layer. Estimated additional effort: 3 months. Considering a lift-and-shift to EC2 as an interim step.

# ── "Spring" Context-Dependent ──

---
id: ambig-006
type: EPISODIC
source: OBSERVED
tags: ambiguous, spring, season, gardening
valence: 5
---
The spring garden planting schedule was finalized: tomatoes go in after the last frost date (April 15), followed by peppers two weeks later. Soil temperature must reach 60°F before planting. Cover crops from winter need to be turned under 3 weeks before planting.

---
id: ambig-007
type: EPISODIC
source: OBSERVED
tags: ambiguous, spring, mechanical, engineering
valence: 0
---
The suspension spring failure on the automated warehouse robot caused $45,000 in damaged inventory. Root cause: spring fatigue after 2.8 million cycles — the manufacturer rated it for 3 million. Switched to titanium alloy springs with 10 million cycle rating.

# ── "ORM" Acronym Clash ──

---
id: ambig-008
type: EPISODIC
source: OBSERVED
tags: ambiguous, orm, risk, finance
valence: -5
---
The Operational Risk Management framework flagged 12 control deficiencies in Q3. Three were rated as high-risk: inadequate disaster recovery testing, insufficient vendor risk assessment for cloud providers, and missing data classification policy for PII.

# ── "Python" Context Clash ──

---
id: ambig-009
type: EPISODIC
source: OBSERVED
tags: ambiguous, python, reptile, nature
valence: 0
---
The Burmese python population in the Everglades has decimated native mammal populations. Radio telemetry tracking of 40 tagged pythons revealed they travel up to 15 miles from their release point. Removal efforts captured 900 pythons last year.

---
id: ambig-010
type: EPISODIC
source: OBSERVED
tags: ambiguous, python, programming
valence: 5
---
Migrated the data science pipeline from Python 3.9 to Python 3.12. Type hints with PEP 695 syntax are much cleaner. Performance improved 15% due to the new specializing adaptive interpreter. All 340 unit tests pass after fixing deprecated asyncio.get_event_loop() calls.
