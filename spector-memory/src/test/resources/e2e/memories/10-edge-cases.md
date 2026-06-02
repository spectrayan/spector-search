# Edge Case & Boundary Condition Memories
# Adversarial content for stress-testing ingestion and recall algorithms

---
id: edge-001
type: EPISODIC
source: OBSERVED
tags: edge-case, minimal
valence: 0
---
OK

---
id: edge-002
type: EPISODIC
source: OBSERVED
tags: edge-case, minimal
valence: 0
---
Yes, that works.

---
id: edge-003
type: EPISODIC
source: OBSERVED
tags: edge-case, duplicate, database, postgresql
valence: -20
---
PostgreSQL connection pool exhaustion caused a production outage lasting 45 minutes. The HikariCP pool was configured with only 10 connections but the service was receiving 500 concurrent requests. Increased pool size to 50 and added connection timeout of 30 seconds.

---
id: edge-004
type: EPISODIC
source: OBSERVED
tags: edge-case, near-duplicate, database, postgresql
valence: -15
---
The PostgreSQL connection pool ran out of available connections, leading to a 45-minute service disruption. HikariCP was set to only 10 max connections while handling 500 concurrent requests. We bumped it to 50 connections and set a 30-second timeout.

---
id: edge-005
type: EPISODIC
source: OBSERVED
tags: edge-case, unicode, internationalization
valence: 5
---
日本語のエラーメッセージを実装しました。Spring MessageSourceを使用して、すべてのバリデーションエラーを日本語に翻訳。UTF-8エンコーディングが重要です。

---
id: edge-006
type: EPISODIC
source: OBSERVED
tags: edge-case, unicode, internationalization
valence: 0
---
Implemented Arabic right-to-left text rendering in the notification service. مرحبا بالعالم — the BiDi algorithm requires special handling for mixed LTR/RTL content in email templates.

---
id: edge-007
type: EPISODIC
source: OBSERVED
tags: edge-case, unicode, emoji
valence: 10
---
Added emoji support to the chat service 🎉🚀💻. The key challenge was that some emoji are multi-codepoint sequences (👨‍💻 is actually 3 codepoints joined by ZWJ). Fixed by using Character.codePointCount() instead of String.length().

---
id: edge-008
type: EPISODIC
source: OBSERVED
tags: edge-case, extreme-valence
valence: 127
---
The most successful deployment in company history: zero-downtime migration of 50 million user records from MongoDB to PostgreSQL. Completed in 4 hours with zero data loss, full backward compatibility, and no customer-facing impact. The team received a standing ovation.

---
id: edge-009
type: EPISODIC
source: OBSERVED
tags: edge-case, extreme-valence
valence: -128
---
Catastrophic data loss incident: a junior developer ran DROP TABLE users in the production database console. The backup system had silently failed 3 weeks earlier. 2.3 million user records permanently lost. Company stock dropped 15% the next day.

---
id: edge-010
type: EPISODIC
source: OBSERVED
tags: edge-case, long-content, architecture, design
valence: 5
---
Comprehensive microservices migration plan document: Phase 1 involves decomposing the monolithic order processing module into three bounded contexts — Order Placement (handles cart, checkout, payment initiation), Order Fulfillment (handles inventory reservation, warehouse routing, shipping label generation, carrier API integration, and tracking number assignment), and Order Lifecycle (handles status transitions, cancellation workflow, refund processing, and return merchandise authorization). Each bounded context communicates via domain events published to Apache Kafka. The event schema uses CloudEvents specification with Avro serialization and schema evolution via the Confluent Schema Registry. Phase 2 involves implementing the Saga pattern for distributed transactions across these three contexts, using choreography-based sagas for simple flows and orchestration-based sagas (via Temporal workflow engine) for complex multi-step processes like international order fulfillment that involves customs declaration, multi-currency payment settlement, and cross-border shipping logistics. Phase 3 focuses on observability: distributed tracing with OpenTelemetry, business metrics dashboards in Grafana, and automated anomaly detection using Prometheus recording rules with PromQL aggregations. The entire migration is estimated to take 6 months with a team of 8 engineers.

---
id: edge-011
type: EPISODIC
source: OBSERVED
tags: edge-case, special-chars
valence: 0
---
The SQL query was: SELECT * FROM users WHERE name = 'O''Brien' AND role = 'admin'; -- this needed escaping. We also found injection attempts like: ' OR 1=1; DROP TABLE sessions; --

---
id: edge-012
type: EPISODIC
source: OBSERVED
tags: edge-case, special-chars, html
valence: -5
---
XSS vulnerability found in the comment system. User input was rendered without sanitization: <script>alert('XSS')</script>. Fixed by implementing Content Security Policy headers and using the OWASP Java HTML Sanitizer for all user-generated content.

---
id: edge-013
type: SEMANTIC
source: REFLECTED
tags: edge-case, many-tags, database, postgresql, performance, optimization, indexing, monitoring, tuning, configuration, vacuum, replication
valence: 10
---
PostgreSQL performance tuning checklist: shared_buffers (25% of RAM), effective_cache_size (75% of RAM), work_mem (256MB for analytics), maintenance_work_mem (512MB), max_parallel_workers_per_gather (4), random_page_cost (1.1 for SSD), checkpoint_completion_target (0.9), wal_buffers (64MB). Always EXPLAIN ANALYZE before and after tuning.

---
id: edge-014
type: EPISODIC
source: OBSERVED
tags: edge-case, numbers, metrics
valence: 0
---
Performance benchmarks: p50=12ms, p90=45ms, p99=180ms, p99.9=450ms. Throughput: 15,000 RPS sustained, 25,000 RPS burst. Error rate: 0.02%. Memory: 1.2GB heap, 400MB off-heap. GC: ZGC with 0.5ms average pause time.

---
id: edge-015
type: EPISODIC
source: OBSERVED
tags: edge-case, code-block
valence: 5
---
The fix was a one-line change in the connection pool configuration: hikari.setMaximumPoolSize(Math.max(Runtime.getRuntime().availableProcessors() * 2, 10)); This dynamically sizes the pool based on CPU cores instead of using a hardcoded value.

---
id: edge-016
type: EPISODIC
source: OBSERVED
tags: edge-case, mixed-language
valence: 0
---
Multilingual error messages implemented using ICU MessageFormat. Example: "Der Benutzer {0} hat {1,number} Nachrichten" for German, "用户{0}有{1,number}条消息" for Chinese. The tricky part was plural rules — Arabic has 6 plural categories versus English's 2.

---
id: edge-017
type: EPISODIC
source: OBSERVED
tags: edge-case, whitespace
valence: 0
---
Discovered a bug where trailing whitespace in API keys caused authentication failures. The key "sk-abc123 " (with trailing space) was stored correctly but comparison failed because the incoming header value was trimmed. Fixed by trimming both sides during comparison.

---
id: edge-018
type: SEMANTIC
source: REFLECTED
tags: edge-case, similar-to-procedure
valence: 5
---
Rule of thumb for connection pool sizing: never exceed 2× the number of CPU cores for OLTP workloads. For mixed workloads, split into two pools — one for fast queries (small pool) and one for batch operations (larger pool with longer timeouts).

---
id: edge-019
type: EPISODIC
source: OBSERVED
tags: edge-case, url-content
valence: 0
---
The webhook URL https://api.example.com/hooks/v2/events?token=abc123&format=json was incorrectly URL-encoded in the database, causing all webhook deliveries to fail with 400 Bad Request. The ampersand was stored as &amp; instead of &.

---
id: edge-020
type: EPISODIC
source: OBSERVED
tags: edge-case, empty-adjacent
valence: 0
---
Debugging a mysterious null pointer: the configuration file had an invisible BOM (byte order mark) character at the beginning, causing the YAML parser to treat the first key as a different string. Solved by configuring the editor to save without BOM and adding a CI check.
