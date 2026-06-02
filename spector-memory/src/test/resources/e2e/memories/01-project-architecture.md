# Project Architecture Memories
# Tier: EPISODIC | Source: OBSERVED | Tags: architecture, design, spring

---
id: arch-001
type: EPISODIC
source: OBSERVED
tags: architecture, design, spring, microservices
valence: 10
---
The application uses a Spring Boot microservices architecture with three main services: user-service, order-service, and notification-service. Each service has its own PostgreSQL database following the database-per-service pattern.

---
id: arch-002
type: EPISODIC
source: OBSERVED
tags: architecture, design, api
valence: 5
---
REST API endpoints follow the /api/v1/{resource} naming convention. All responses use a standard envelope format with status, data, and error fields. HATEOAS links are included for resource navigation.

---
id: arch-003
type: EPISODIC
source: OBSERVED
tags: architecture, spring, configuration
valence: 0
---
Configuration management uses Spring Cloud Config with a Git-backed repository. Environment-specific properties are stored in profiles: application-dev.yml, application-staging.yml, and application-prod.yml.

---
id: arch-004
type: EPISODIC
source: OBSERVED
tags: architecture, design, messaging
valence: 5
---
Inter-service communication uses Apache Kafka for asynchronous events and gRPC for synchronous calls. The event schema uses Avro with a central Schema Registry to ensure backward compatibility.

---
id: arch-005
type: EPISODIC
source: OBSERVED
tags: architecture, security, design
valence: 0
---
The API gateway handles authentication using JWT tokens issued by the auth-service. Rate limiting is configured at 1000 requests per minute per client. Circuit breakers protect downstream services from cascade failures.

---
id: arch-006
type: EPISODIC
source: OBSERVED
tags: architecture, monitoring, observability
valence: 5
---
Observability stack includes Prometheus for metrics collection, Grafana for dashboards, and Jaeger for distributed tracing. Each service exposes /actuator/health and /actuator/prometheus endpoints.

---
id: arch-007
type: EPISODIC
source: OBSERVED
tags: architecture, design, caching
valence: 0
---
Redis is used as the shared caching layer with a 15-minute TTL for frequently accessed data. Cache invalidation follows a write-through pattern where the service updates both the database and cache atomically.

---
id: arch-008
type: EPISODIC
source: USER_STATED
tags: architecture, preferences
valence: 15
---
The team prefers convention over configuration. All new services should be generated from the internal Spring Boot archetype template which includes standard error handling, logging configuration, and health check endpoints.

---
id: arch-009
type: EPISODIC
source: OBSERVED
tags: architecture, testing
valence: 0
---
Integration tests use Testcontainers for PostgreSQL and Kafka. Contract tests between services use Spring Cloud Contract. The CI pipeline requires 80% code coverage minimum.

---
id: arch-010
type: EPISODIC
source: OBSERVED
tags: architecture, design, database
valence: 0
---
Database migrations are managed by Flyway with version-prefixed SQL files. Each service maintains its own migration history. Cross-service queries are forbidden; use API calls or events instead.
