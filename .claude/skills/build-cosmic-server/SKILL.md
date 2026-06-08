---
name: build-cosmic-server
description: Compile and test the Cosmic MapleStory Java server on this Mac. Use when building, compiling, or running Maven tests for this project, or when `java`/`mvn`/`mvnw` is needed and the environment isn't set up. Covers JDK 21 install, JAVA_HOME, the mvnw permission gotcha, and verified build/test commands.
---

# Build the Cosmic Java Server

The Cosmic server requires **Java 21** (`<java.version>21</java.version>` in `pom.xml`). There is no system `mvn` — use the bundled `./mvnw` wrapper. Run all commands from the repo root.

## One-time environment setup

```bash
# JDK 21 (keg-only at /opt/homebrew/opt/openjdk@21)
brew install openjdk@21

# The wrapper ships without the executable bit — fix once, or it fails with
# "permission denied" (exit 126).
chmod +x mvnw
```

## Every build: export the toolchain

`mvnw` needs `JAVA_HOME`. Export these in the shell before any Maven command:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # expect: openjdk version "21.0.x"
```

## Verified commands

```bash
# Compile main + test sources (fast sanity check; deps cached after first run)
./mvnw clean test-compile

# Full build, skip tests
./mvnw clean install -DskipTests

# Run a single test class
./mvnw test -Dtest=PlannerDecisionTest

# Run all tests
./mvnw test
```

## Known-good results (2026-05-26)

- `./mvnw clean test-compile` → `BUILD SUCCESS` (~11s with deps cached)
- `./mvnw test -Dtest=PlannerDecisionTest` → `Tests run: 10, Failures: 0, Errors: 0`

## Gotchas

- **`permission denied: ./mvnw` (exit 126)** → run `chmod +x mvnw`.
- **"Unable to locate a Java Runtime"** → `JAVA_HOME`/`PATH` not exported, or JDK 21 not installed.
- First `./mvnw` run downloads all dependencies — can take several minutes. Subsequent runs are fast.
- Running the *live* server additionally needs MySQL 8+ and (for LLM features) Ollama. See the `SKILL.md` in the parent project directory for full server/DB/client setup.
