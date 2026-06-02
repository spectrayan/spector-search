# Database Work Memories
# Tier: EPISODIC + SEMANTIC | Tags: database, postgresql, migration

---
id: db-001
type: EPISODIC
source: OBSERVED
tags: database, postgresql, performance
valence: -20
---
PostgreSQL connection pool exhaustion caused a production outage lasting 45 minutes. The HikariCP pool was configured with only 10 connections but the service was receiving 500 concurrent requests. Increased pool size to 50 and added connection timeout of 30 seconds.

---
id: db-002
type: EPISODIC
source: OBSERVED
tags: database, postgresql, migration
valence: 0
---
Created Flyway migration V15__add_user_preferences_table.sql that adds a jsonb column for storing user preferences. Added a GIN index on the jsonb column for efficient querying of nested attributes.

---
id: db-003
type: EPISODIC
source: OBSERVED
tags: database, postgresql, debugging, error
valence: -30
---
Deadlock detected in PostgreSQL when two transactions tried to update the same order and inventory rows in opposite order. Fixed by establishing a consistent lock ordering: always lock inventory first, then order. Added advisory locks for critical sections.

---
id: db-004
type: EPISODIC
source: OBSERVED
tags: database, postgresql, optimization
valence: 10
---
Query optimization reduced the order search from 4.2 seconds to 120 milliseconds by adding a composite index on (customer_id, created_at DESC) and rewriting the subquery as a lateral join.

---
id: db-005
type: SEMANTIC
source: REFLECTED
tags: database, postgresql, pattern
valence: 10
---
When dealing with PostgreSQL jsonb queries, always use the @> containment operator instead of ->> text extraction for indexed lookups. The GIN index only supports containment checks, not arbitrary text comparisons.

---
id: db-006
type: EPISODIC
source: OBSERVED
tags: database, postgresql, replication
valence: -15
---
Replication lag between primary and read replica reached 30 seconds during peak load, causing stale reads in the user-service. Switched critical reads to the primary connection and implemented a replication-aware routing layer.

---
id: db-007
type: EPISODIC
source: OBSERVED
tags: database, redis, caching
valence: 5
---
Redis cache hit rate improved from 45% to 92% after implementing a tiered caching strategy: hot data in local Caffeine cache (1 minute TTL), warm data in Redis (15 minute TTL), and cold data served from PostgreSQL.

---
id: db-008
type: SEMANTIC
source: REFLECTED
tags: database, postgresql, best-practice
valence: 15
---
Always use parameterized queries to prevent SQL injection. Never concatenate user input into SQL strings. Use Spring Data JPA's @Query annotation with named parameters for complex queries.

---
id: db-009
type: EPISODIC
source: OBSERVED
tags: database, postgresql, backup
valence: 0
---
Automated daily backups configured with pg_dump running at 2 AM UTC. Point-in-time recovery enabled with WAL archiving to S3. Backup retention set to 30 days. Monthly restore drills scheduled.

---
id: db-010
type: EPISODIC
source: OBSERVED
tags: database, postgresql, schema
valence: 0
---
Implemented soft delete pattern using a deleted_at timestamp column. All repository queries automatically filter out soft-deleted records using a @Where annotation. Hard delete runs as a scheduled batch job every 90 days.

---
id: db-011
type: EPISODIC
source: OBSERVED
tags: database, postgresql, connection, error
valence: -25
---
Connection refused errors started appearing after deploying to Kubernetes. Root cause was the PostgreSQL service DNS name changed during the cluster migration. Updated the JDBC URL to use the new service name and added connection validation query.

---
id: db-012
type: SEMANTIC
source: REFLECTED
tags: database, postgresql, indexing
valence: 10
---
Partial indexes in PostgreSQL are extremely effective for queries that filter on a common condition. For example, CREATE INDEX idx_active_orders ON orders(customer_id) WHERE status != 'CANCELLED' saves space and speeds up the most common query path.

---
id: db-013
type: EPISODIC
source: OBSERVED
tags: database, postgresql, migration, error
valence: -20
---
Flyway migration V18 failed in production because it tried to add a NOT NULL column without a default value to a table with 2 million rows. Rolled back and split into two migrations: first add nullable column with default, then backfill, then add constraint.

---
id: db-014
type: EPISODIC
source: OBSERVED
tags: database, postgresql, vacuum
valence: -10
---
Table bloat on the events table reached 40% due to aggressive updates without autovacuum tuning. Adjusted autovacuum_vacuum_scale_factor to 0.01 and autovacuum_vacuum_threshold to 1000. Ran manual VACUUM FULL during maintenance window.

---
id: db-015
type: SEMANTIC
source: REFLECTED
tags: database, pattern, connection-pool
valence: 10
---
HikariCP connection pool sizing formula: connections = ((core_count * 2) + effective_spindle_count). For a 4-core server with SSD, optimal pool size is approximately 10. Oversizing the pool causes contention and actually degrades performance.
