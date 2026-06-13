---
name: verify-bot-change
description: End-to-end loop to verify a bot/planner/combat code change actually works on the live server — build, restart, spawn a test bot, monitor, and report. Use after editing any server.bots.* Java (planner, combat, control API, build logic) when you need to prove the change behaves, not just compiles. Ties together build-cosmic-server, spawn-test-bot, edit-bot-goal, and monitor-bot-logs.
---

# Verify a Bot Change End-to-End

Compiling is not verifying. A Java change to `server.bots.*` only proves out by
running on the live server and watching a real bot. This skill is the loop that turns
"it builds" into "it behaves," with evidence. It composes four sibling skills —
read them for the detail; this is the orchestration.

## When a restart is actually required

- **Java code change** (planner, combat, control API, BotManager, build logic) →
  rebuild the fat jar + restart the server. The server runs from `target/Cosmic.jar`.
- **SOUL.md / GOAL.md / planner-prompt template / active.txt** → NO restart. These are
  read live (mtime-cached). Use the `edit-bot-goal` skill and verify via `/brain`.
- **BotLlmConfig field** → it's a compiled default, so a change to the source needs a
  rebuild+restart (there's no runtime config endpoint for most fields).

If you only touched context files, skip to step 4.

## The loop

### 1. Build (see `build-cosmic-server`)
```bash
cd /Users/ndbellisario/Projects/Maplestory_Bot_Project_v2/cosmic
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q test -Dtest=PlannerDecisionTest,PlannerPromptLibraryTest   # fast unit gate first
./mvnw -q clean package -DskipTests                                   # -> target/Cosmic.jar
```
Don't restart if the build failed — fix first. Run any unit test relevant to your change
before paying for a full restart cycle.

### 2. Restart the live server
```bash
pkill -f "net.server.Server"; sleep 3
# matches how it's run today; logs to cosmic/logs/cosmic-log.log
nohup java -Xmx4g -cp target/Cosmic.jar net.server.Server > /dev/null 2>&1 &
# wait for online (bounded), then sanity-check
until grep -q "Cosmic is now online" logs/cosmic-log.log; do sleep 2; done; tail -1 logs/cosmic-log.log
```
Confirm: `curl -s http://127.0.0.1:8485/health` → `{"ok":true,"plannerEnabled":...,"model":...}`.
A restart drops all ownerless bots (they don't auto-respawn) — you re-spawn in step 3.

> MySQL must be up (it usually already is). If the server dies at boot on DB, start it:
> `cosmic/start-mysql.sh`. WZ data must be present (`cosmic/wz/`).

### 3. Spawn a fresh test bot (see `spawn-test-bot`)
```bash
curl -s -X POST http://127.0.0.1:8485/bots/spawn \
  -H 'Content-Type: application/json' -d '{"name":"Rudy","mapId":104040000}'
# note the returned "id"
```
For a behavior change, set up the scenario via `edit-bot-goal` BEFORE or right after
spawning (context files reload live), so the bot's goal exercises your change.

### 4. Monitor for the expected behavior (see `monitor-bot-logs`)
Use a bounded watch that exits on a verdict — never an open tail you walk away from:
```bash
L=logs/cosmic-log.log
( tail -n0 -f "$L" & echo $! >/tmp/tailpid ) | \
  grep -m1 -E "planner\[Rudy\] decision=|ERROR|Exception|unparseable|no response"
kill "$(cat /tmp/tailpid)" 2>/dev/null
```
Cross-check live state and the exact context the bot ran on:
```bash
curl -s http://127.0.0.1:8485/bots                 # status/idle/map/level — what it's doing
curl -s http://127.0.0.1:8485/bots/<id>/decisions  # the decision trail (action+reason)
curl -s http://127.0.0.1:8485/bots/<id>/brain      # the SOUL/GOAL/prompt — what it saw
```
Expect a decision within ~60s (sweep 8s, ownerless heartbeat 60s). For combat changes,
"hunting" shows as `status:grinding` in `/bots`, not per-swing log lines — confirm via
status + rising `exp`/`level`, and check `combat.*` loggers only fire on errors.

### 5. Clean up + report
```bash
curl -s -X POST http://127.0.0.1:8485/bots/<id>/despawn
```
Report with EVIDENCE, verdict first:
1. **Verdict** — did the change produce the intended behavior? yes/no.
2. **Build/test** — unit result + BUILD SUCCESS.
3. **Behavior proof** — the exact post-change `decision=` / `status` / `exp` line(s)
   with timestamps, plus the `/brain` excerpt showing the bot saw the right input.
4. **Before/after** if relevant (the old vs new decision for the same situation).
5. **Anomalies** — any ERROR/timeout/`no response`, even if the happy path worked.

Never report success from a green build alone — only from observed live behavior.

## Quick decision tree
- Only edited SOUL/GOAL/prompt? → steps 4–5 (no build/restart). Verify via `/brain`.
- Edited Java but want a fast check? → step 1 unit test, then full loop.
- Bot won't spawn after restart? → it must be a DB character (`spawn-test-bot` prereq),
  and `plannerEnabled` must be true (`/health`).
- No decisions at all? → check `/health` plannerEnabled, Ollama model loaded
  (`curl http://localhost:11434/api/tags`), bot present in `/bots`.

## Gotchas
- **Forgetting the rebuild:** editing `.java` then restarting WITHOUT `mvnw package` runs
  the OLD jar — your change isn't in it. Always package before restart for code changes.
- **Stale bot from before restart:** restart clears ownerless bots; always re-spawn, and
  don't trust a `/bots` entry that predates your restart.
- **Cold Ollama:** first decision after restart can be slow / log one `no response (…ms)`
  then recover as the model loads. Not a failure on its own.
- **Mac slept mid-test:** `no response (1000000+ ms)` + Hikari "clock leap" = sleep, benign.
```
