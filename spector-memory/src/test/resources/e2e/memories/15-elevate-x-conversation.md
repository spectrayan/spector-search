# Agentic Coding Conversation — AI Fitness Platform Development
# Real-world sessions: Building a cross-platform AI fitness app
# Covers KMP architecture, AI workout generation, safety guardrails,
# testing, Firebase patterns, accessibility, and cloud pipeline.
# Based on actual development sessions — sanitized for security

# ══════════════════════════════════════════════════════════════
# SESSION 1: KMP Shared Module Setup
# ══════════════════════════════════════════════════════════════

---
id: conv-101
type: EPISODIC
source: OBSERVED
tags: conversation, user-request, ai-workout, kmp, mobile, architecture
valence: 5
---
USER: We need to implement the AI workout plan generation feature. The KMP shared module should contain the prompt builder and response parser in commonMain. The AI generates personalized weekly workout plans based on user goals, equipment availability, fitness level, and injury history. Use Firebase AI Logic with the Gemini model for structured JSON output. Build for Android, iOS, and Web targets.

---
id: conv-102
type: PROCEDURAL
source: REFLECTED
tags: conversation, agent-action, kmp, architecture, domain-model, jsexport
valence: 10
---
AGENT ACTION: Set up the KMP shared module directory structure under commonMain with packages: domain/model (WorkoutPlan, Exercise, MuscleGroup, Equipment), domain/enum (FitnessGoal, ExperienceLevel, MuscleCategory, WorkoutCategory), domain/validation (PasswordValidator, AgeValidator, OnboardingValidator), usecase (RegisterUseCase, LoginUseCase, WorkoutGenerationUseCase), repository (AuthRepository, UserRepository, WorkoutRepository, AuditRepository), ai/prompt (WorkoutPromptBuilder, NutritionPromptBuilder), ai/parser (WorkoutResponseParser with structured JSON extraction), sync (OfflineQueueManager), ratelimit (RateLimitChecker), theme (ThemeManager), and unit (UnitConverter, UnitSystem). All public API classes annotated with @JsExport for web interop. Repository interfaces return DataResult<T> for consistent error handling across platforms.

---
id: conv-103
type: EPISODIC
source: OBSERVED
tags: conversation, debugging, jsexport, kmp, web, typescript, interop
valence: -10
---
AGENT DEBUGGING: Encountered critical issues with KMP-to-JavaScript interop for the Angular web app. Problem 1: suspend functions cannot be annotated with @JsExport — the Kotlin/JS compiler rejects them at compile time. Problem 2: Flow<T> is not representable in JavaScript module exports. Problem 3: Complex generic types like Result<WorkoutPlan> don't export cleanly to TypeScript. Implemented three workarounds: (1) Wrap suspend functions in Promise using GlobalScope.promise{} — every UseCase gets a corresponding *Async companion that returns Promise<T>. (2) Convert Flow to callback-based API using fun observeWorkouts(callback: (Array<WorkoutSession>) -> Unit). (3) Replace Result<T> with @JsExport sealed class JsResult with Success and Error subclasses carrying serializable payloads. The Angular web app imports from @spectrayan/elevate-x-shared NPM package and calls all KMP functions successfully.

# ══════════════════════════════════════════════════════════════
# SESSION 2: AI Safety Guardrails Implementation
# ══════════════════════════════════════════════════════════════

---
id: conv-104
type: PROCEDURAL
source: REFLECTED
tags: conversation, agent-action, ai-safety, guardrails, workout, nutrition, validation
valence: 15
---
AGENT IMPLEMENTATION: Built the AI safety guardrail decision tree as a validation pipeline that runs on every AI-generated response before it reaches the user. Workout safety branch: (1) Beginner detection — users with less than 4 weeks of logged activity are flagged as beginners. (2) HIIT cap — beginners limited to maximum 3 HIIT sessions per week, enforced by the WorkoutSafetyValidator scanning each DailyWorkout for high-intensity markers. (3) Injury avoidance — if the user's profile contains injured muscle groups, the validator removes all exercises targeting those muscles and their synergists using a muscle group dependency graph (e.g., injured shoulder also removes overhead press, lateral raises, and front raises). Nutrition safety branch: (4) Calorie floor enforcement — absolute minimum 800 calories per day regardless of user input. Gender-adjusted floors: 1200 cal for female users, 1500 for male. Plans below these thresholds are blocked entirely with a safety message. (5) Allergen flagging — cross-references generated meal ingredients against the user's declared allergens. AI coach scope: (6) Out-of-scope detection — medical questions, injury diagnosis, or mental health crisis content triggers an immediate refusal response that redirects to healthcare professionals. All responses include a mandatory disclaimer.

---
id: conv-105
type: SEMANTIC
source: REFLECTED
tags: conversation, architecture, disclaimer, compliance, medical, fitness
valence: 5
---
ARCHITECTURE: Every AI-generated response in the fitness platform must include the following mandatory disclaimer appended to the output: "This content is generated by AI for informational purposes only. It does not replace professional medical advice, diagnosis, or treatment. Always consult a qualified healthcare professional before starting any fitness or nutrition program." The disclaimer is appended at the response rendering layer, not in the AI prompt, to ensure it cannot be manipulated or removed by prompt injection. The disclaimer text is stored in a centralized configuration resource, not hardcoded, so it can be localized and updated without code changes.

# ══════════════════════════════════════════════════════════════
# SESSION 3: Property-Based Testing Strategy
# ══════════════════════════════════════════════════════════════

---
id: conv-106
type: EPISODIC
source: OBSERVED
tags: conversation, user-request, testing, property-based, kotest, validators
valence: 5
---
USER: We need property-based testing for all validators and calculators. Use Kotest with at least 100 iterations per property. Test password validation, BMI calculation, one-rep-max estimation, TDEE calorie calculation, and the safety guardrails. Every calculation must be mathematically correct across the full input range including edge cases.

---
id: conv-107
type: PROCEDURAL
source: REFLECTED
tags: conversation, agent-action, testing, property-based, kotest, arb, validation
valence: 10
---
AGENT IMPLEMENTATION: Created 11 property-based test suites in commonTest using Kotest kotest-property with minimum 100 iterations each. Test suites: (1) Password validation using Arb.string() and Arb.stringPattern("[A-Za-z0-9!@#$%^&*]{0,128}") — verifies acceptance of valid passwords and rejection of weak passwords across random inputs. (2) BMI calculation with Arb.double(30.0..300.0) for weight and Arb.double(100.0..250.0) for height — verifies BMI = weight / (height/100)^2 within 0.01 tolerance. (3) One-rep-max Epley formula: 1RM = weight * (1 + reps/30) — verifies monotonic increase with reps and weight. (4) TDEE calculation using Mifflin-St Jeor equation with activity multiplier — verifies output within physiologically plausible range (800-6000 cal). (5) Rate limiter fairness: generates random request sequences and verifies no user exceeds their configured limit. (6) Calorie floor enforcement: generates random NutritionPlan objects and verifies none violate the gender-adjusted minimums. (7) Age validation with Arb.localDate() — verifies age >= 13 requirement. (8) Unit converter roundtrip: metric -> imperial -> metric preserves value within 0.01 tolerance for weight, height, and distance. (9) Workout volume calculation: total_volume = sum(sets * reps * weight) verified against independent calculation. (10) Session management invariants: max 5 concurrent sessions per user. (11) Offline sync conflict resolution: last-write-wins with vector clock tiebreaker.

# ══════════════════════════════════════════════════════════════
# SESSION 4: Firebase Architecture and Offline Strategy
# ══════════════════════════════════════════════════════════════

---
id: conv-108
type: SEMANTIC
source: REFLECTED
tags: conversation, architecture, unit-system, metric, imperial, storage, conversion
valence: 10
---
ARCHITECTURE DECISION: All physical measurements are stored in canonical metric units in Firestore and Kotlin data classes. Field names stay clean without unit suffixes — inline comments document the canonical unit. Weight stored in kilograms, height in centimeters, distance in meters, duration in seconds. Conversion to the user's preferred unit system (METRIC or IMPERIAL, stored in their settings document) happens exclusively at the UI display layer using UnitConverter in KMP commonMain/unit/. Critical rule: when converting canonical to display units, weights are rounded to nearest 0.5 to prevent floating-point drift across roundtrips (e.g., 100kg -> 220.462lbs -> 100.003kg accumulates error over repeated saves). The rounding to 0.5 increments is also more natural for gym weights. No field suffix convention is used — engineers rely on inline Kotlin doc comments and Firestore schema descriptions.

---
id: conv-109
type: EPISODIC
source: OBSERVED
tags: conversation, architecture-decision, firebase, offline, sync, mobile, web
valence: -5
---
ARCHITECTURE DECISION: Critical trade-off for Firebase offline persistence. Mobile apps have offline persistence enabled by default — workout logs cached in local SQLite-backed Firestore cache and synced when connectivity returns. This is essential because users log workouts at gyms with poor connectivity. However, the Angular web app intentionally does NOT enable offline persistence. Three reasons: (1) Firestore's web offline cache uses IndexedDB which can grow unbounded and hit browser-specific storage quotas unpredictably. (2) The mobile-first user base primarily logs workouts on phones — web is used for dashboard viewing and plan management, not real-time workout logging. (3) Service Worker-based offline adds complexity to Angular's build pipeline with questionable ROI for the web use case. Documented as Architecture Decision Record ADR-007.

---
id: conv-110
type: SEMANTIC
source: REFLECTED
tags: conversation, architecture, firestore, naming, security, performance
valence: 5
---
FIRESTORE CONVENTIONS: Collection naming uses kebab-case (workout-sessions, community-feed, body-stats) — never camelCase or snake_case. Security rules enforce user isolation — users can only read and write their own data documents. Community posts are readable by all authenticated users but writable only by the author. Server-side subscription validation prevents free-tier users from accessing premium features even if the client UI is modified. Performance patterns: distributed counters using the shard pattern for high-write fields like post likes and comment counts. Cursor-based pagination for unbounded collections. Composite indexes pre-created for common queries (userId + createdAt). Pre-computed top-N leaderboards using Cloud Functions to avoid expensive client-side aggregation.

# ══════════════════════════════════════════════════════════════
# SESSION 5: Angular + TailwindCSS v4 Conflicts
# ══════════════════════════════════════════════════════════════

---
id: conv-111
type: EPISODIC
source: OBSERVED
tags: conversation, debugging, angular, tailwind, scss, styling, css
valence: -5
---
AGENT DEBUGGING: Found a styling conflict between TailwindCSS v4 and Angular Material 3 components. The @apply directive does NOT work inside component .scss files — Tailwind utility classes must be used directly in component templates only. Root cause: TailwindCSS v4 uses a pure CSS file with @import 'tailwindcss', which is processed by the CSS pipeline separately from the SCSS compilation pipeline. The SCSS compiler runs before the Tailwind PostCSS plugin, so @apply references are unresolved at SCSS compile time. Fix: Ensure stylesheet order in Angular project.json configuration is tailwind.css FIRST (pure CSS file), then styles.scss SECOND. This ordering is critical because Material 3 theme variables defined in SCSS need to resolve after Tailwind's reset layer. Never place @import 'tailwindcss' inside an SCSS file. Documented in development patterns knowledge base as a critical gotcha.

# ══════════════════════════════════════════════════════════════
# SESSION 6: Cloud Run Pre-Generation Pipeline
# ══════════════════════════════════════════════════════════════

---
id: conv-112
type: PROCEDURAL
source: REFLECTED
tags: conversation, implementation, cloud-run, pipeline, ai, serverless, scheduling
valence: 10
---
AGENT IMPLEMENTATION: Built the Cloud Run pre-generation pipeline for AI workout plans. Architecture flow: Cloud Scheduler fires a daily cron trigger at 4 AM user-local-time (timezone-aware scheduling). The trigger publishes a message to a Pub/Sub topic. Cloud Run receives the message containing a batch of active user IDs. First filter: only process users who logged in within the last 7 days (prevents wasting AI compute on churned users). For each active user: (1) Load user profile including fitness goals, equipment, injuries, and experience level. (2) Build the prompt using WorkoutPromptBuilder from KMP shared logic. (3) Call Firebase AI Logic (Gemini) for structured JSON generation. (4) Parse response with WorkoutResponseParser. (5) Run through WorkoutSafetyValidator. (6) Write the validated plan to Firestore at users/{uid}/workout-plans/{weekId}. Failed generations are retried 3 times with exponential backoff, then sent to a dead-letter queue for manual review. Cloud Run configured with min_instances=1 to avoid cold starts and max_instances=10 with concurrency=80 for batch processing.

# ══════════════════════════════════════════════════════════════
# SESSION 7: Accessibility Standards
# ══════════════════════════════════════════════════════════════

---
id: conv-113
type: SEMANTIC
source: REFLECTED
tags: conversation, accessibility, wcag, mobile, web, compose, angular, compliance
valence: 10
---
ACCESSIBILITY STANDARDS: Platform targets WCAG 2.1 AA compliance on all surfaces. Mobile (Compose Multiplatform) requirements: TalkBack (Android) and VoiceOver (iOS) screen reader support with semantics {} modifiers on every interactive element, minimum 44x44pt touch targets enforced by a custom Lint rule, text scaling support up to 200% without layout breaking, high contrast toggle in accessibility settings that switches the Material 3 color scheme to high-contrast variants. Web (Angular) requirements: full ARIA role/label attributes on all custom components, semantic HTML5 elements (nav, main, article, aside), complete keyboard navigation with visible focus indicators and logical tab order, browser zoom support tested to 200%, prefers-contrast and prefers-reduced-motion media query support. Color contrast ratios: 4.5:1 for normal text (< 18pt), 3:1 for large text (>= 18pt). The choice of Angular over Compose for Web was primarily driven by accessibility — Compose Web renders to HTML canvas via Skia, which fundamentally breaks screen readers, browser find (Ctrl+F), password manager autofill, SEO crawling, and all DOM-based assistive technologies. This makes Compose Web non-viable for any application requiring WCAG compliance.

---
id: conv-114
type: SEMANTIC
source: REFLECTED
tags: conversation, architecture, angular, component-standards, signals, standalone
valence: 5
---
ANGULAR STANDARDS: All components must be standalone (no NgModules), use OnPush change detection strategy, and use Signals for reactive state management instead of BehaviorSubject. Dependency injection uses inject() function instead of constructor injection. Templates use Angular's modern control flow syntax: @if, @for, @switch instead of *ngIf, *ngFor, [ngSwitch]. All routes are lazy loaded using loadComponent(). Component selectors use the ex- prefix (e.g., ex-workout-card, ex-meal-planner, ex-progress-chart). Feature modules are organized under apps/frontend/web/app/src/app/features/ with separate directories for auth, workout, nutrition, progress, community, and settings. Shared reusable components live under shared/components/ with accompanying shared/pipes/ and shared/directives/.
