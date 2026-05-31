---
description: Audit and harden exception handling for a feature or module, aligning all catch/throw sites with the SpectorException framework.
---

## Trigger

When the user asks to:
- "Add exception handling" for a newly implemented feature
- "Harden exceptions" in a module
- "Audit error handling" for recent changes
- "Align with the error framework" after building a new feature

## Prerequisites

Read the exception handling section of the coding standards skill:
`.agents/skills/coding-standards/SKILL.md` → **Error Handling — SpectorException Framework**

## Steps

### 1. Identify Target Scope

Determine what needs hardening:
- A specific feature (e.g., "exception handling for the entity graph")
- A module (e.g., "audit spector-memory")
- Recent changes (e.g., "harden exceptions for the feature we just built")

List all files involved. Focus on:
- New classes added for the feature
- Pipeline integration points (ingestion, recall, consolidation)
- Persistence layers (save/load methods)
- Public API methods

### 2. Audit Existing Catch Sites

Search for anti-patterns in the target files:

```bash
# Find generic Exception catches
grep -rn "catch (Exception " spector-{module}/src/main/java/

# Find UncheckedIOException throws
grep -rn "UncheckedIOException" spector-{module}/src/main/java/

# Find generic RuntimeException throws
grep -rn "throw new RuntimeException" spector-{module}/src/main/java/

# Find string concatenation in ErrorCode.format()
grep -rn "ErrorCode\.\w*\.format(" spector-{module}/src/main/java/ | grep "+"

# Find swallowed exceptions
grep -rn "catch.*{" spector-{module}/src/main/java/ -A1 | grep -B1 "// ignored\|/\* \*/"
```

### 3. Determine Required Error Codes

For each error condition identified:

1. Check if an existing `ErrorCode` covers it (search `ErrorCode.java`)
2. If not, add a new code under the correct category section:
   - Validation: `SPE-100-xxx`
   - Config: `SPE-110-xxx`
   - Index: `SPE-200-xxx`
   - Storage: `SPE-210-xxx`
   - Embedding: `SPE-300-xxx`
   - Memory: `SPE-310-xxx`
   - GPU: `SPE-400-xxx`
   - Server: `SPE-500-xxx`
   - Client: `SPE-510-xxx`
   - Ingestion: `SPE-600-xxx`
   - Cluster: `SPE-700-xxx`
   - Internal: `SPE-900-xxx`

**Template:**
```java
/** Brief javadoc of when this error occurs. */
FEATURE_OPERATION_FAILED  (310_0XX, ErrorCategory.MEMORY,
        "Feature operation failed for {}: {}"),
```

### 4. Create Granular Exception Classes

For each distinct error domain, create a `Spector{Domain}Exception`:

**Location:** `spector-{module}/src/main/java/.../error/`

**Template:**
```java
public class Spector{Domain}Exception extends Spector{Parent}Exception {
    private final String operation;

    public Spector{Domain}Exception(String operation) {
        super(ErrorCode.FEATURE_OPERATION_FAILED, operation);
        this.operation = operation;
    }

    public Spector{Domain}Exception(String operation, Throwable cause) {
        super(ErrorCode.FEATURE_OPERATION_FAILED, cause, operation);
        this.operation = operation;
    }

    public String getOperation() { return operation; }
}
```

**Rules:**
- Constructor args map 1:1 to `{}` placeholders in the ErrorCode template
- No string concatenation — `errorCode.format(args)` handles formatting
- Add typed context fields (operation, path, etc.)
- Follow naming: `Spector{Domain}Exception`

### 5. Fix Throw Sites

Replace all anti-patterns with proper throws:

| Before (❌) | After (✅) |
|---|---|
| `throw new UncheckedIOException(msg, e)` | `throw new Spector{Domain}Exception(args, e)` |
| `throw new RuntimeException(msg)` | `throw new Spector{Domain}Exception(args)` |
| `throw new SpectorException(ErrorCode.X, e, "concat" + var)` | `throw new Spector{Domain}Exception(arg1, arg2, e)` |

### 6. Fix Catch Sites

Apply the correct pattern based on context:

**Graceful degradation** (enrichment steps that should NOT crash the pipeline):
```java
} catch (RuntimeException e) {
    Spector{Domain}Exception ex = new Spector{Domain}Exception("operation", e);
    log.warn(ex.getMessage());
}
```

**IO failures** (persistence methods):
```java
} catch (IOException e) {
    throw new Spector{Domain}PersistenceException("GraphType", path, e);
}
```

**Validation** (public API entry points):
```java
if (param == null) {
    throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "paramName");
}
```

**Never:**
- `catch (Exception e)` — always catch the narrowest type (`RuntimeException`, `IOException`)
- `catch (Exception e) { /* ignored */ }` — never swallow exceptions
- String concatenation inside `ErrorCode.format()` or exception constructors

### 7. Update Exception Hierarchy Documentation

If new exception classes were added, update the hierarchy tree in:
- `SpectorException.java` javadoc (lines 26-49)

### 8. Verify

```bash
# Compile
mvn compile -q

# Run tests for the affected module
mvn test -pl spector-{module}

# Verify no remaining anti-patterns
grep -rn "catch (Exception " spector-{module}/src/main/java/ | grep -v "// justified"
grep -rn "throw new RuntimeException" spector-{module}/src/main/java/
grep -rn "UncheckedIOException" spector-{module}/src/main/java/
```

### 9. Commit

Follow the incremental-commits skill (`.agents/skills/incremental-commits/SKILL.md`):

```
feat(error): add {domain} exception hierarchy with ErrorCode integration

New error codes: SPE-XXX-YYY through SPE-XXX-ZZZ
New exceptions: Spector{A}Exception, Spector{B}Exception
Hardened catch sites in: {list of files}
```
