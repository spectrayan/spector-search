---
description: Process for adding, removing, or renaming Maven modules in the Spector Search reactor.
---

# Workflow: Module Lifecycle

Process for adding, removing, or renaming Maven modules in the Spector Search reactor.

## Trigger

When creating a new `spector-*` module, removing an obsolete module, or renaming/merging modules.

---

## Adding a New Module

### 1. Create Directory Structure

```
spector-{name}/
├── pom.xml
├── README.md
└── src/
    ├── main/java/com/spectrayan/spector/{name}/
    └── test/java/com/spectrayan/spector/{name}/
```

### 2. Create POM

- Parent: `com.spectrayan:spector-search:0.1.0-SNAPSHOT`
- Add dependencies from the correct architecture layer
- Include `--add-modules jdk.incubator.vector` in compiler args if needed

### 3. Add to Root POM

Add `<module>spector-{name}</module>` to root `pom.xml` in the correct layer position.

### 4. Create README

Include: purpose, architecture, usage examples, API summary.

### 5. Create Docs Page

Create `docs/docs/modules/spector-{name}.md`:
```markdown
--8<-- "spector-{name}/README.md"
```

### 6. Update Nav

Add to `docs/mkdocs.yml` under `Modules:` in correct position.

### 7. Update Module Index

Edit `docs/docs/modules/index.md`:
- Add to correct layer table
- Add to Mermaid architecture diagram
- Add to dependency graph diagram

### 8. Verify

```bash
mvn compile -pl spector-{name}
cd docs && python -m mkdocs build --clean
```

### 9. Commit

```
feat({name}): add spector-{name} module
```

---

## Removing a Module

### 1. Delete Module

```bash
rm -rf spector-{name}/
```

### 2. Remove from Root POM

Delete `<module>spector-{name}</module>` from root `pom.xml`.

### 3. Remove Dependencies

Grep for references in other module POMs:
```bash
grep -rn "spector-{name}" spector-*/pom.xml
```

### 4. Clean Docs

- Delete `docs/docs/modules/spector-{name}.md`
- Remove nav entry from `docs/mkdocs.yml`
- Remove from `docs/docs/modules/index.md` tables and diagrams

### 5. Grep for Stale References

```bash
grep -rn "spector-{name}" docs/ scripts/ *.md
```

### 6. Verify

```bash
mvn clean compile
cd docs && python -m mkdocs build --clean
```

### 7. Commit

```
refactor: remove spector-{name} module

<reason for removal>
```

---

## Renaming / Merging Modules

Follow "Adding" for the new name, then "Removing" for the old name. Commit separately:

1. `feat({new}): add spector-{new} module`
2. Migration commit (move code, update imports)
3. `refactor: remove spector-{old} module (merged into spector-{new})`
