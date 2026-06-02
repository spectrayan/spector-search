# Entity Graph Memories — People, Projects, and Relationships
# Designed for entity extraction and multi-hop graph traversal testing

---
id: entity-001
type: EPISODIC
source: OBSERVED
tags: team, project, people
valence: 5
---
Alice Chen manages the Project Phoenix migration from monolith to microservices. She leads a team of 6 engineers and reports to VP of Engineering Bob Martinez. The project uses Spring Boot and PostgreSQL.

---
id: entity-002
type: EPISODIC
source: OBSERVED
tags: team, project, people
valence: 0
---
David Kim authored the authentication module for Project Phoenix. He implemented the JWT token service and OAuth2 integration. David collaborates closely with Alice Chen on security architecture decisions.

---
id: entity-003
type: EPISODIC
source: OBSERVED
tags: team, project, people
valence: 10
---
Sarah Johnson created the performance testing framework used by Project Phoenix. The framework uses Gatling for load testing and integrates with the Grafana dashboard for real-time monitoring during tests.

---
id: entity-004
type: EPISODIC
source: OBSERVED
tags: team, project, infrastructure
valence: 0
---
Project Phoenix depends on the shared Kubernetes cluster managed by the Platform Team. The cluster runs on AWS EKS with 20 nodes. Infrastructure as Code is maintained using Terraform by engineer Carlos Ramirez.

---
id: entity-005
type: EPISODIC
source: OBSERVED
tags: team, meeting, decision
valence: 5
---
Architecture decision record: Alice Chen decided to use Apache Kafka instead of RabbitMQ for inter-service communication in Project Phoenix. The decision was based on Kafka's superior throughput for event streaming and built-in partitioning for horizontal scaling.

---
id: entity-006
type: EPISODIC
source: OBSERVED
tags: team, project, api
valence: 0
---
The order-service API was designed by David Kim and reviewed by Alice Chen. It exposes 12 REST endpoints for order management. The API documentation is auto-generated using SpringDoc OpenAPI and published to the internal API portal.

---
id: entity-007
type: EPISODIC
source: OBSERVED
tags: team, project, database
valence: -10
---
Carlos Ramirez reported that the PostgreSQL cluster for Project Phoenix needs an upgrade from version 14 to 16. The upgrade is blocked by a breaking change in the JSONB indexing behavior. Alice Chen approved a workaround using explicit cast operators.

---
id: entity-008
type: EPISODIC
source: OBSERVED
tags: team, project, testing
valence: 5
---
Sarah Johnson's performance tests revealed that the notification-service has a memory leak when processing batch email notifications. She traced it to an unclosed HttpClient connection in the email template renderer.
