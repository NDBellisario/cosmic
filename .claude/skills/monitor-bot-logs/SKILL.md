---
name: monitor-bot-logs
description: Monitor the running Cosmic server log and bot control API to watch and report bot behavior. Use when verifying a bot/planner change, debugging why a bot mis-acts, watching autonomous decisions live, or answering "what is the bot doing / did my change work". Covers the log path + rotation, exact logger/grep patterns (planner decisions, goal checklist, combat, nav, errors), live control-API cross-checks, and how to report results.
---

# Monitor & Report on Bot Behavior

The Cosmic server logs almost everything bot-related, and a localhost control API
exposes live bot state. Together they let you watch a bot, verify a change, and
report concrete results instead of guessing. **This is read-only observation** —
it never changes the server.

## Where the log is (verify first)

The running server logs to **`cosmic/logs/cosmic-log.log`** (relative to the repo;
absolute `/Users/ndbellisario/Projects/Maplestory_Bot_Project_v2/cosmic/logs/cosmic-log.log`).

- Config: `src/main/resources/log4j2.xml`. Pattern: `HH:mm:ss.SSS [thread] LEVEL logger{2} - msg`.
- **Rotates at 20 MB** into `logs/YYYY-MM/YYYY-MM-DD/cosmic-log-<timestamp>-<i>.log`.
  So the active file can suddenly shrink — if it looks truncated, the tail rolled
  into the dated subdir. Search rotated files too when looking back in time.
- Do NOT assume `/tmp/cosmic-full.log` — that was an older manual-launch path and may
  not exist. Always confirm the real file the live process writes to:
  ```bash
  lsof -p "$(pgrep -f net.server.Server)" 2>/dev/null | grep cosmic-log.log
  ```

## Logger names that matter (from `logger{2}`)

| Logger | What it tells you |
|---|---|
| `planner.BotAutonomousPlanner` | every autonomous decision, prompt dump (if debugLog), idle/inflight gating, timeouts |
| `planner.GoalChecklist` | GOAL.md checklist items auto-marked done |
| `llm.OllamaClient` | raw SLM request/response problems |
| `llm.BotLlmReplyManager` | chat-reply generation |
| `bots.BotManager` | spawn/despawn, tick failures, bot disabled |
| `bots.BotNavigationManager` | pathing decisions |
| `combat.BotAttackDataProvider` | attack-skill data resolution |
| `control.BotControlApi` | control API start + spawn/despawn calls |
| `pool.HikariPool` | DB pool; "thread starvation/clock leap" = the Mac slept (benign) |

## Core monitoring recipes

```bash
cd /Users/ndbellisario/Projects/Maplestory_Bot_Project_v2/cosmic
L=logs/cosmic-log.log

# Live tail of planner decisions only (the main signal for autonomy work)
tail -f "$L" | grep --line-buffered -E "planner\[.*decision="

# Recent decisions for one bot (exact line format below)
grep -E "planner\[Rudy\] decision=" "$L" | tail -20
#  -> 23:24:44.607 [bot-planner] INFO planner.BotAutonomousPlanner - planner[Rudy] decision=GRIND reason="..." (536ms)

# Goal progress auto-ticks
grep "GoalChecklist" "$L" | tail

# Anything wrong: errors, exceptions, planner trouble, bot disabled
grep -iE "ERROR|Exception|unparseable|no response|tick failed|Disabling bot" "$L" \
  | grep -v "no changesets" | tail -30

# Full planner prompt+response for one decision (only when BotLlmConfig.debugLog=true)
grep -nA2 "planner\[Rudy\] system:" "$L" | tail -40
```

### Watching a change land (the verify loop)
For "did my change work", prefer a **bounded monitor that exits on a verdict** over an
open `tail -f`. Cover BOTH the success signal and the failure signatures so silence
never reads as success:

```bash
# Waits for the next planner decision OR an error, then stops. Returns the line.
( tail -n0 -f "$L" & echo $! >/tmp/tailpid ) | \
  grep -m1 -E "planner\[.*decision=|ERROR|Exception|unparseable|no response"
kill "$(cat /tmp/tailpid)" 2>/dev/null
```
The planner sweeps every ~8s (`plannerIntervalMs`) and force-reevaluates ownerless
bots every ~60s (`plannerHeartbeatMs`), so a decision should appear within ~60s if a
bot is registered and `plannerEnabled=true`. No decision in 2 min → check the
gotchas below, not just the log.

## Cross-check against LIVE state (control API)

The log is history; the control API is now. Both on **`127.0.0.1:8485`** (loopback
only; gated by `BotLlmConfig.botControlApiEnabled`). Use it to confirm the log
reflects reality:

```bash
curl -s http://127.0.0.1:8485/health            # {"ok":true,"plannerEnabled":..,"model":".."}
curl -s http://127.0.0.1:8485/bots              # live list: id,name,mapId,mapName,x,y,hp,level,exp,status,idle
curl -s http://127.0.0.1:8485/bots/2/decisions  # recent decision history (ts,action,reason) for bot id 2
curl -s http://127.0.0.1:8485/bots/2/brain      # the SOUL/GOAL/prompt context the bot is running on
curl -s http://127.0.0.1:8485/maps/104040000/mobs   # live mob positions on a map
```
`status` (grinding/following/idle) + `idle` from `/bots` are the ground truth for
"what is the bot actually doing right now" — pair with the log's `decision=` for the
"why". If `/health` shows `plannerEnabled:false`, no decisions will ever log.

## Reporting results (what to hand back)

When asked to verify/monitor, report concretely — never "looks fine":
1. **Verdict first**: did the expected behavior happen? (e.g. "Yes — Rudy switched to
   GRIND within 9s of the GOAL edit.")
2. **Evidence**: paste the exact `decision=` / `GoalChecklist` / error line(s) with timestamps.
3. **Live state**: the `/bots` entry (map, level, status) confirming the log.
4. **Anomalies**: any ERROR/timeout/`no response`, even if the main path worked.
5. If nothing happened in the expected window, say so plainly and give the likely cause.

## Gotchas

- **No decisions appearing** → check, in order: `plannerEnabled` (`curl .../health`),
  the bot is actually registered (`curl .../bots` non-empty), and the model is loaded
  (`curl http://localhost:11434/api/tags`). A cold Ollama model causes a one-time long
  first call (logs `no response (……ms)` then recovers).
- **`no response (1000000+ ms)`** in the log = the Mac slept; the planner thread's
  wall clock jumped. Benign on wake; not a hang.
- **Log file looks empty/short** → it rotated at 20MB; the older content is under
  `logs/YYYY-MM/YYYY-MM-DD/`.
- **`debugLog`** (BotLlmConfig) gates the full prompt/response dump. Decisions log
  regardless; prompts only when it's true.
- Combat/attack actions are NOT individually logged unless debugLog or a combat
  logger fires — infer hunting from `status:grinding` in `/bots`, not per-swing logs.
- Don't `tail -f` and walk away expecting a notification — use a bounded `grep -m1`
  (above) or report from a snapshot.
```
