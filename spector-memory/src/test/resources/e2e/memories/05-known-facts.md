# Known Facts — Semantic Tier
# Long-term knowledge that the agent has consolidated

---
id: fact-001
type: SEMANTIC
source: REFLECTED
tags: java, spring, pattern
valence: 10
---
Spring Boot auto-configuration works by scanning META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. Custom auto-configurations should use @ConditionalOnClass and @ConditionalOnProperty to activate only when relevant.

---
id: fact-002
type: SEMANTIC
source: REFLECTED
tags: java, concurrency, pattern
valence: 10
---
Java 21 virtual threads should replace platform threads for I/O-bound workloads. Use Executors.newVirtualThreadPerTaskExecutor() for blocking I/O operations. Avoid synchronized blocks with virtual threads — use ReentrantLock instead to prevent thread pinning.

---
id: fact-003
type: SEMANTIC
source: REFLECTED
tags: java, memory, performance
valence: 10
---
The Java Foreign Function and Memory API (Panama) enables zero-copy off-heap memory access. Use Arena.ofShared() for long-lived allocations and Arena.ofConfined() for thread-local temporary buffers. Always close arenas to prevent memory leaks.

---
id: fact-004
type: SEMANTIC
source: REFLECTED
tags: database, postgresql, performance
valence: 10
---
PostgreSQL EXPLAIN ANALYZE output should be read bottom-up. Key metrics: actual time (wall clock per node), rows (actual vs estimated), and loops (number of executions). Seq Scan on large tables usually indicates a missing index.

---
id: fact-005
type: SEMANTIC
source: REFLECTED
tags: kubernetes, networking, pattern
valence: 5
---
Kubernetes Service types: ClusterIP for internal communication, NodePort for development, LoadBalancer for external traffic, and ExternalName for DNS aliasing. Prefer Ingress controllers over LoadBalancer services for HTTP traffic.

---
id: fact-006
type: SEMANTIC
source: REFLECTED
tags: java, testing, pattern
valence: 10
---
Test pyramid best practice: 70% unit tests, 20% integration tests, 10% end-to-end tests. Unit tests should be fast and isolated using mocks. Integration tests use real dependencies via Testcontainers. E2E tests validate critical user journeys only.

---
id: fact-007
type: SEMANTIC
source: REFLECTED
tags: spring, security, pattern
valence: 10
---
Spring Security filter chain ordering matters. The SecurityFilterChain beans are processed in @Order sequence. Place CORS filter before authentication, and the custom JWT filter before UsernamePasswordAuthenticationFilter.

---
id: fact-008
type: SEMANTIC
source: REFLECTED
tags: java, design-pattern
valence: 5
---
The Builder pattern is preferred over telescoping constructors when a class has more than 4 optional parameters. Use records for immutable value objects with fewer fields. Consider sealed interfaces for constrained hierarchies.

---
id: fact-009
type: SEMANTIC
source: REFLECTED
tags: database, caching, pattern
valence: 10
---
Cache invalidation strategies: write-through (update cache on write), write-behind (async cache update), and cache-aside (lazy load on read miss). For read-heavy workloads, cache-aside with TTL is the safest default. Event-driven invalidation for strong consistency.

---
id: fact-010
type: SEMANTIC
source: REFLECTED
tags: monitoring, observability, pattern
valence: 5
---
The four golden signals of monitoring: latency, traffic, errors, and saturation. Every service should expose these as Prometheus metrics. Alert on symptom-based signals (error rate > 1%) rather than cause-based signals (CPU > 90%).

---
id: fact-011
type: SEMANTIC
source: REFLECTED
tags: java, spring, dependency-injection
valence: 5
---
Constructor injection is preferred over field injection in Spring. It makes dependencies explicit, enables immutability, and simplifies testing. Use @RequiredArgsConstructor from Lombok to reduce boilerplate.

---
id: fact-012
type: SEMANTIC
source: REFLECTED
tags: api, rest, versioning
valence: 5
---
API versioning strategies: URL path versioning (/api/v1/), header versioning (Accept-Version), and content negotiation. URL path versioning is the most common and easiest to understand. Maintain at most two active versions simultaneously.
