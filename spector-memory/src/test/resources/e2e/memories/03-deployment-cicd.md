# Deployment & CI/CD Memories — Temporal Sequence
# These memories form a natural temporal chain for chain extension testing

---
id: deploy-001
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, kubernetes
valence: 0
---
Started deployment of order-service v2.3.1 to the staging Kubernetes cluster. Pre-deployment checklist completed: database migrations verified, feature flags configured, rollback plan documented.

---
id: deploy-002
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, testing
valence: 5
---
All integration tests passed in the staging environment. Load test with 1000 concurrent users showed p99 latency of 180ms, within the 200ms SLA. Memory usage stable at 512MB heap.

---
id: deploy-003
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, kubernetes, error
valence: -40
---
Production deployment of order-service v2.3.1 failed catastrophically. Pod crash loop detected — OOMKilled after 3 minutes. The new Avro serialization library had a memory leak in batch processing mode. Immediate rollback initiated.

---
id: deploy-004
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, rollback
valence: -20
---
Rollback to order-service v2.3.0 completed in 4 minutes. Kubernetes rolling update strategy ensured zero-downtime rollback. All health checks passed. Incident severity classified as P2 — no data loss but 8 minutes of degraded service.

---
id: deploy-005
type: EPISODIC
source: OBSERVED
tags: deploy, debugging, error, postmortem
valence: -15
---
Post-mortem analysis revealed the Avro memory leak was caused by the new SpecificDatumWriter caching class loaders indefinitely. The staging load test didn't catch it because it ran for only 5 minutes, but the leak manifested after 15+ minutes of sustained traffic.

---
id: deploy-006
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, fix
valence: 20
---
Fixed the Avro memory leak by upgrading to avro-1.11.4 which includes the class loader cache fix. Added a 30-minute soak test to the CI pipeline to catch similar memory leaks before production deployment.

---
id: deploy-007
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, kubernetes, canary
valence: 10
---
Implemented canary deployment strategy for all production releases. New versions receive 5% of traffic initially, with automatic promotion to 100% after 15 minutes if error rate stays below 0.1% and p99 latency stays below 200ms.

---
id: deploy-008
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, pipeline
valence: 5
---
CI pipeline now includes: compile → unit test → integration test → contract test → security scan → container build → staging deploy → soak test → manual approval → canary production deploy. Total pipeline time: ~25 minutes.

---
id: deploy-009
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, infrastructure
valence: 0
---
Migrated from Jenkins to GitHub Actions for CI/CD. Build times reduced from 18 minutes to 12 minutes due to better caching and parallel job execution. Self-hosted runners on ARM64 machines for cost efficiency.

---
id: deploy-010
type: EPISODIC
source: OBSERVED
tags: deploy, kubernetes, scaling
valence: 5
---
Configured Horizontal Pod Autoscaler for all services. CPU target utilization set to 70%, memory target to 80%. Min replicas: 2, max replicas: 10. Scaling response time improved with custom metrics from Prometheus.

---
id: deploy-011
type: EPISODIC
source: OBSERVED
tags: deploy, kubernetes, error, dns
valence: -25
---
Service discovery failure after Kubernetes cluster upgrade. CoreDNS pods were evicted during the upgrade, causing 3 minutes of DNS resolution failures. Added PodDisruptionBudget for CoreDNS to prevent all replicas from being evicted simultaneously.

---
id: deploy-012
type: EPISODIC
source: OBSERVED
tags: deploy, cicd, security
valence: 0
---
Added Trivy container image scanning to the CI pipeline. Found 3 critical CVEs in the base image (eclipse-temurin:21). Upgraded to the latest patched version and added a policy to block deployments with critical vulnerabilities.
