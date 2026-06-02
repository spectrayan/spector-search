# User Preferences and Working Context

---
id: pref-001
type: EPISODIC
source: USER_STATED
tags: preferences, ui, settings
valence: 20
---
The user prefers dark mode in all development tools. Their preferred IDE is IntelliJ IDEA with the Dracula theme. Font: JetBrains Mono 14pt. Line spacing: 1.4. They dislike auto-import and prefer manual import management.

---
id: pref-002
type: EPISODIC
source: USER_STATED
tags: preferences, coding, style
valence: 15
---
The user prefers functional programming style in Java when possible. They use Stream API extensively and prefer map/filter/reduce over imperative loops. However, they draw the line at nested flatMap chains — those should be refactored into named methods.

---
id: pref-003
type: EPISODIC
source: USER_STATED
tags: preferences, communication
valence: 10
---
The user prefers concise, technical communication. They want code examples over lengthy explanations. When presenting options, they want a clear recommendation with rationale. They dislike being asked obvious clarifying questions.

---
id: pref-004
type: EPISODIC
source: USER_STATED
tags: preferences, tools, git
valence: 10
---
Git workflow preference: trunk-based development with short-lived feature branches. Commit messages follow Conventional Commits format. Squash merge to main. Branch naming: feature/TICKET-description or fix/TICKET-description.

---
id: pref-005
type: EPISODIC
source: USER_STATED
tags: preferences, documentation
valence: 5
---
The user values self-documenting code over comments. Comments should explain why, not what. Javadoc is required for public API methods. README files should include quick-start instructions and architecture diagrams.

---
id: pref-006
type: EPISODIC
source: USER_STATED
tags: preferences, testing
valence: 10
---
Testing preferences: AssertJ over JUnit assertions for readability. Given-When-Then structure for test methods. Test names should describe behavior, not method names. Prefer @Nested classes for organizing related tests.

---
id: working-001
type: WORKING
source: OBSERVED
tags: current, thinking, task
valence: 0
---
Currently investigating the order-service timeout issue. Hypothesis: the new batch processing endpoint is holding database connections too long. Need to check the connection pool metrics and add timeout configuration.

---
id: working-002
type: WORKING
source: OBSERVED
tags: current, context, task
valence: 0
---
Today's priority: fix the batch processing timeout, then review David Kim's PR for the notification service refactor. After lunch: architecture review meeting for the new analytics pipeline.

---
id: working-003
type: WORKING
source: OBSERVED
tags: current, note
valence: 0
---
Note to self: check if the Flyway migration V20 needs a backfill script for the new customer_tier column. The marketing team wants to segment users by tier starting next sprint.

---
id: zeigarnik-001
type: EPISODIC
source: OBSERVED
tags: task, unresolved, debugging
valence: -10
---
UNRESOLVED: There is an intermittent NullPointerException in the notification-service that occurs approximately once per 1000 requests. Stack trace points to the template rendering engine but the input data looks valid. Suspect a race condition in the template cache.
