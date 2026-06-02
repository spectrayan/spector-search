# Temporal Sequence Memories
# Closely related events for temporal chain, recency, and contradiction testing

# ── Incident Timeline: Payment Service Outage ──

---
id: temporal-001
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, monitoring
valence: -10
---
Alert triggered: payment-service error rate spiked to 5.2% at 14:32 UTC. PagerDuty notification sent to on-call engineer. Grafana dashboard showed sudden increase in HTTP 503 responses from the payment gateway.

---
id: temporal-002
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, debugging
valence: -15
---
Initial investigation at 14:35 UTC revealed payment-service pods were healthy but the upstream Stripe API was returning 503 Service Unavailable. Checked Stripe status page — confirmed ongoing incident affecting their European region.

---
id: temporal-003
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, mitigation
valence: -5
---
At 14:40 UTC, activated the payment failover circuit breaker. Queued all pending payments in the dead-letter queue for retry. Notified customers via in-app banner about temporary payment delays.

---
id: temporal-004
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, resolution
valence: 10
---
Stripe confirmed service restoration at 15:15 UTC. Disabled circuit breaker and initiated dead-letter queue replay. All 847 queued payments processed successfully within 12 minutes. No financial discrepancies detected.

---
id: temporal-005
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, postmortem
valence: 5
---
Post-incident review at 16:00 UTC: total downtime was 43 minutes, impact limited to European payment processing. Action items: implement multi-region payment gateway failover, add Stripe health check to circuit breaker conditions, improve DLQ monitoring dashboard.

---
id: temporal-006
type: EPISODIC
source: OBSERVED
tags: temporal, incident, payment, improvement
valence: 15
---
Implemented dual-gateway payment processing: Stripe as primary, Adyen as secondary failover. Automatic gateway switching when primary error rate exceeds 2% for 60 seconds. Tested successfully with chaos engineering injection.

# ── Contradictory Knowledge Chain ──

---
id: temporal-007
type: SEMANTIC
source: REFLECTED
tags: temporal, contradiction, caching, redis
valence: 5
---
Redis Cluster is the best approach for horizontal scaling of our caching layer. It provides automatic sharding across 6 nodes, handles failover transparently, and supports our 50GB cache dataset. Migration from standalone Redis completed successfully.

---
id: temporal-008
type: SEMANTIC
source: REFLECTED
tags: temporal, contradiction, caching, redis
valence: -10
---
Redis Cluster turned out to be problematic for our use case. Cross-slot operations fail with CROSSSLOT errors, making our multi-key cache invalidation patterns impossible. Lua scripts can't span slots. Considering reverting to Redis Sentinel with application-level sharding.

---
id: temporal-009
type: SEMANTIC
source: REFLECTED
tags: temporal, contradiction, caching, redis
valence: 10
---
Final decision on Redis architecture: switched to Redis Sentinel with consistent hash ring at the application layer. This gives us full Lua script support, multi-key operations, and manual shard rebalancing. Performance is actually 15% better than Cluster for our access patterns.

# ── Correction Chain: Understanding a Technology ──

---
id: temporal-010
type: SEMANTIC
source: REFLECTED
tags: temporal, correction, graalvm, performance
valence: 5
---
GraalVM Native Image reduces startup time from 2.3 seconds to 45 milliseconds — perfect for our serverless functions. Memory footprint drops from 180MB to 35MB. Planning to migrate all Lambda functions.

---
id: temporal-011
type: SEMANTIC
source: REFLECTED
tags: temporal, correction, graalvm, performance
valence: -5
---
GraalVM Native Image has significant limitations we didn't anticipate: reflection-heavy Spring Boot code requires extensive configuration, build times increased from 30 seconds to 8 minutes, and dynamic proxies for JPA repositories don't work without manual registration. Migration paused.

---
id: temporal-012
type: SEMANTIC
source: REFLECTED
tags: temporal, correction, graalvm, performance
valence: 10
---
Successfully migrated 3 of 12 Lambda functions to GraalVM Native Image using Spring Boot 3.3's improved AOT support. The functions with simple REST endpoints work perfectly. Complex functions with JPA remain on JVM mode. Hybrid approach is the right answer — not all-or-nothing.
