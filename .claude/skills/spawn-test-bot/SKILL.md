---
name: spawn-test-bot
description: Spawn or despawn an ownerless autonomous bot via the Cosmic control API for testing, with no human client logged in. Use when you need a live bot to test a planner/combat/goal change, or to clean one up afterward. Covers the exact spawn/despawn requests, the DB-character prerequisite, picking a map, and confirming the bot is alive and deciding.
---

# Spawn / Despawn a Test Bot (no client needed)

The Cosmic control API on **`127.0.0.1:8485`** (loopback only) spawns *ownerless*
bots — they run the planner and hunt with no human logged in. This is the fast path
to get a live bot for testing without the v83 client. The API is gated by
`BotLlmConfig.botControlApiEnabled` (default true); confirm with `curl .../health`.

## Prerequisite: the bot must exist as a DB character

Spawning loads an existing character by name from the DB — it does **not** create one.
An agent-data folder (`bots/agent-data/<Name>/`) alone is NOT enough.

```bash
# Which names are actually spawnable (characters in the DB):
/opt/homebrew/opt/mysql/bin/mysql --no-defaults -h127.0.0.1 -uroot -p1234 -N \
  -e "USE cosmic; SELECT id,name,level FROM characters;"
```
If the character doesn't exist yet, it must be created in-game (`@spawnbot <name> confirm`
from a logged-in owner, once) or via whatever character-creation path exists — the
control API cannot conjure a new character. SOUL.md/GOAL.md folders are separate
(see the `edit-bot-goal` skill); a spawned bot with no folder just runs the built-in prompt.

## Spawn

```bash
# Minimal: spawn by name, server picks the map (usually the char's saved map)
curl -s -X POST http://127.0.0.1:8485/bots/spawn \
  -H 'Content-Type: application/json' -d '{"name":"Rudy"}'

# Spawn into a specific map (e.g. Henesys Hunting Ground I = 104040000)
curl -s -X POST http://127.0.0.1:8485/bots/spawn \
  -H 'Content-Type: application/json' -d '{"name":"Rudy","mapId":104040000}'
```
Success → `{"ok":true,"id":<charId>,"name":"Rudy","mapId":104040000,"mapName":"Henesys Hunting Ground I"}`.
Note the returned **`id`** — every other call uses the bot's character id, not its name.

Errors come back as `{"ok":false,"error":"..."}` with HTTP 400 — common causes: name
not a DB character (e.g. a folder exists but no character → `"No character named 'X' exists."`),
or a bad mapId.

> Spawning a bot that's ALREADY spawned is idempotent — it returns `{"ok":true}` and
> re-attaches to the running bot (verified). So a spawn call is safe to repeat; it won't
> error or create a duplicate. To force a fresh start, despawn first, then spawn.

## Confirm it's alive and deciding

```bash
curl -s http://127.0.0.1:8485/bots                 # the new bot appears with status/idle
curl -s http://127.0.0.1:8485/bots/<id>/decisions  # decision history fills within ~60s
```
A freshly spawned bot should produce a planner decision within ~60s (sweep ~8s,
ownerless heartbeat ~60s). To watch it happen, use the `monitor-bot-logs` skill's
bounded-tail recipe on `planner[<Name>] decision=`. If no decision appears, check
`curl .../health` for `plannerEnabled:true` and that Ollama has the model loaded.

## Despawn (clean up after a test)

```bash
curl -s -X POST http://127.0.0.1:8485/bots/<id>/despawn   # {"ok":true} or 404 if gone
```
Always despawn test bots you spawned so the next test starts clean and you're not
burning SLM calls on a forgotten bot. Verify with `curl .../bots` (it should drop out).

## Other live controls (read the `monitor-bot-logs` skill for the full map)

```bash
curl -s -X POST http://127.0.0.1:8485/bots/<id>/move \
  -H 'Content-Type: application/json' -d '{"mapId":104040000}'   # live-move to a map
curl -s http://127.0.0.1:8485/bots/<id>/brain                    # full SOUL/GOAL/prompt the bot runs on
curl -s http://127.0.0.1:8485/maps/104040000/mobs                # live mob positions
```

## Useful map ids
- `104040000` — Henesys Hunting Ground I (low-level grind, dense mobs; where Rudy runs)
- `100000000` — Henesys (town, for REST_IN_TOWN / vendor tests)
Query the world-index (`world-index/`) for more, or `curl .../maps/<id>/mobs` to probe.

## Gotchas
- The API is **loopback only** — call it from the Mac, not the client machine.
- Token: if `BotLlmConfig.botControlApiToken` is non-empty, add `-H "X-Panel-Token: <token>"`.
  Default is empty (open to localhost).
- Ownerless bots persist until despawned or the server restarts — they do not auto-respawn on boot.
```
