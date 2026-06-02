# Learned Procedures — Procedural Tier

---
id: proc-001
type: PROCEDURAL
source: REFLECTED
tags: procedure, debugging, rule
valence: 10
---
Debugging production issues procedure: 1) Check the Grafana error rate dashboard. 2) Inspect recent deployments in ArgoCD. 3) Search Kibana logs filtered by trace-id. 4) Check PostgreSQL slow query log. 5) Review recent config changes in Git.

---
id: proc-002
type: PROCEDURAL
source: REFLECTED
tags: procedure, deploy, rule
valence: 10
---
Production deployment procedure: 1) Merge PR to main branch. 2) Wait for CI pipeline to pass. 3) Verify staging deployment. 4) Create release tag. 5) Approve canary deployment. 6) Monitor error rate for 15 minutes. 7) Full rollout or rollback.

---
id: proc-003
type: PROCEDURAL
source: REFLECTED
tags: procedure, database, migration, rule
valence: 15
---
Database migration safety rules: 1) Never add NOT NULL columns without defaults. 2) Always make migrations reversible. 3) Test on a copy of production data first. 4) Deploy schema changes separately from application changes. 5) Use online DDL for large tables.

---
id: proc-004
type: PROCEDURAL
source: REFLECTED
tags: procedure, incident, rule
valence: 10
---
Incident response procedure: 1) Acknowledge the page within 5 minutes. 2) Assess severity (P1-P4). 3) Communicate status in the #incidents Slack channel. 4) Mitigate first, root cause later. 5) Write post-mortem within 48 hours with action items.

---
id: proc-005
type: PROCEDURAL
source: REFLECTED
tags: procedure, code-review, rule
valence: 5
---
Code review checklist: 1) Does the PR have tests? 2) Are error paths handled? 3) Is logging adequate but not excessive? 4) Are there any SQL injection risks? 5) Does it follow the team's naming conventions? 6) Is the change backward compatible?

---
id: proc-006
type: PROCEDURAL
source: REFLECTED
tags: procedure, security, rule
valence: 15
---
Security incident response: 1) Isolate affected systems immediately. 2) Preserve logs and evidence. 3) Notify the security team lead. 4) Rotate all potentially compromised credentials. 5) Conduct forensic analysis. 6) File compliance report if data breach confirmed.

---
id: proc-007
type: PROCEDURAL
source: REFLECTED
tags: procedure, performance, rule
valence: 10
---
Performance investigation procedure: 1) Check p99 latency trends in Grafana. 2) Enable profiling with async-profiler. 3) Analyze flame graph for CPU hotspots. 4) Check GC logs for long pauses. 5) Review database query plans for regressions.

---
id: proc-008
type: PROCEDURAL
source: REFLECTED
tags: procedure, onboarding, rule
valence: 5
---
New service onboarding checklist: 1) Create repository from template. 2) Configure CI/CD pipeline. 3) Set up Datadog monitoring. 4) Add service to the service mesh. 5) Create runbook in Confluence. 6) Schedule architecture review meeting.
