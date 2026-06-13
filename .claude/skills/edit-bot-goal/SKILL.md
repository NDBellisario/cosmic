---
name: edit-bot-goal
description: Author or change a bot's SOUL.md (personality) and GOAL.md (daily plan), then confirm the live planner picked the change up. Use when steering a bot's behavior, fixing a mis-acting bot by tightening its goal, or setting up a test scenario. Covers the file layout, the GOAL format the planner actually parses, how the live reload works, and how to verify the change took effect.
---

# Edit a Bot's SOUL / GOAL and Verify It Took

A bot's behavior comes from two files the planner reads every decision tick. Editing
them is the main steering lever — no rebuild, no restart. **The catch is verification:**
the planner caches files by mtime and only re-reads on change, so always confirm the
new content is live before concluding a change "worked."

## File layout

```
cosmic/bots/agent-data/<BotName>/
  SOUL.md   # WHO the bot is — personality, speech, preferences (stable)
  GOAL.md   # WHAT to do today — priorities + checklist the planner follows (changes often)
```
`BotName` matches the bot's character name exactly. A bot with no folder runs the
built-in prompt; SOUL/GOAL are optional but are how you get specific behavior.

> A SOUL/GOAL folder does NOT create a spawnable bot — the character must exist in the
> DB (see the `spawn-test-bot` skill). Folder = behavior; DB character = existence.

## GOAL.md format the planner actually uses

The planner injects GOAL.md into the user prompt and parses the **checklist** so it can
auto-tick items (`planner.GoalChecklist`). Keep it in this shape — it's what the live
prompt expects:

```markdown
# GOAL — <BotName> (today)

## Priorities (top first)
1. Reach level 34, then stop hunting. While your Level is below 34: GRIND.
   Once your Level is 34 or higher: REST_IN_TOWN, then IDLE and wait for orders.
2. Keep at least 30 HP pots on hand (BUY_POTIONS when low).
3. While leveling and your bag is FULL: SELL, then keep hunting.
4. Don't die. If HP is low and no pots, REST_IN_TOWN.

## Checklist
- [ ] Reach level 34
- [ ] Once level 34+, travel to town and wait for orders

## Notes
Below level 34 -> GRIND (SELL when bag full, BUY_POTIONS when low).
Level 34+ -> finished hunting: REST_IN_TOWN, then IDLE and wait.
```

What makes goals the SLM follows reliably (learned from Rudy):
- **State the action verbs the planner knows** (GRIND, GRIND_AT, TRAVEL_TO, FOLLOW_OWNER,
  STOP, BUY_POTIONS, SELL, TURN_IN_QUEST, REST_IN_TOWN, IDLE) — the model maps onto these.
- **Make level/threshold conditions explicit** ("while Level below 34 -> GRIND; at 34+ ->
  REST_IN_TOWN"). Vague targets cause the inconsistency where a level-38 bot still says
  "below level 34, keep hunting." Compare-to-current-level wording fixes it.
- `- [ ]` / `- [x]` checklist lines are parsed by item number; the planner reports a
  `"done":[n]` it completed and `GoalChecklist` ticks the box. Don't renumber mid-run.

## Editing

Two equivalent paths:
1. **Debug panel** — Context Files tab → pick bot/file → edit → Save (atomic write).
   Also has a "New bot" button that scaffolds SOUL+GOAL. Requires `PANEL_WRITES_ENABLED=true`.
2. **Direct file edit** — edit `cosmic/bots/agent-data/<BotName>/GOAL.md`. Prefer an
   atomic write (write temp, rename) if scripting, to avoid the planner reading a
   half-written file mid-tick. The Edit/Write tools are fine for hand edits.

Keep GOAL.md **plain ASCII** where practical — the server's config tokenizer is fussy
about exotic unicode elsewhere; goals are safer kept simple (em-dashes in prose are fine
in agent-data, but avoid smart quotes in anything the server parses strictly).

## Verify the change is LIVE (do not skip)

The planner re-reads on mtime change, but confirm what the bot is *actually* running:

```bash
# The single source of truth: the exact GOAL/SOUL/prompt the bot is using right now.
curl -s http://127.0.0.1:8485/bots/<id>/brain | python3 -m json.tool | sed -n '1,40p'
#   -> check the "input" (contains your GOAL text) and "system" fields reflect the edit
```
Then watch the next decision change accordingly (see `monitor-bot-logs`):
```bash
tail -f cosmic/logs/cosmic-log.log | grep --line-buffered -E "planner\[<BotName>\] (decision=|.*checklist item)"
```
A decision consistent with the new goal within ~60s = success. If `/brain` still shows
the OLD goal text, the planner hasn't re-read — the file mtime didn't change (e.g. you
wrote identical bytes) or you edited the wrong bot folder.

## Reporting
State: (1) what you changed, (2) the `/brain` excerpt proving the new text is live,
(3) the first post-edit `decision=` line showing the behavior shift, (4) any
`GoalChecklist ... item N done` ticks. Don't claim success from the file edit alone —
only from `/brain` + a decision.

## Gotchas
- **Forgot which bots have folders vs DB characters:** `ls cosmic/bots/agent-data/` lists
  folders; `SELECT name FROM characters` lists spawnable ones. They can differ.
- **No decision change after edit:** mtime unchanged (identical content), wrong folder
  name (must match character name exactly), or the bot isn't spawned.
- **SLM ignores a goal line:** usually the wording isn't actionable — rephrase around the
  known action verbs and explicit thresholds, then re-verify.
```
