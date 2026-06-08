# Ownerless Hunting — Refactor Plan

Branch: `ownerless-hunting` (off `master` @ d44997721)

## Goal
Make ownerless bots (panel-spawned via the control API, no logged-in owner) **hunt
for real** — find mobs, path via the nav graph, attack, kill, gain exp, and survive —
by reusing the **same battle-tested combat the owner-driven path runs**, not a
hand-rolled subset.

## Diagnosis (why the first attempt failed)
The `owner == null` branch in `BotManager.tickCore` returns *before* the full grind
logic, which lives at **`tickCore` lines 2084–2382** (`if (entry.grinding) { ... }`).
That block has nav-graph pathing, jump-to-target, fall recovery, ranged spacing, and
loot. A reimplementation lacked all of it → bots couldn't reach mobs and fell off
ledges; exp never accrued. See memory `ownerless-combat-findings`.

Two specific bugs (already understood):
1. Sentinel `ownerCharId` = the bot's own id makes `resolveTickOwner` resolve the bot
   as its own owner → it never enters the `owner == null` branch. Use sentinel `0`.
2. The `owner == null` branch never decrements the attack cooldown
   (`BotCombatManager.tickActionLock`), so attacks freeze after the first hit.

## Decisions (locked)
- **Approach: extract the grind block** into a shared method (vs. relaxing the guard).
  Keeps owner-driven behavior provably unchanged (pure move).
- **Survival: include auto-pot/heal in v1** so hunting bots sustain.

## Concrete anchors (verified)
- Grind block to extract: `BotManager.tickCore` **lines 2084–2382**.
- Survival/owner-independent systems (in `runCommonTickSystems`, ~3359–3387):
  - `BotCombatManager.tickActionLock(entry)` — attack-cooldown decrement (bug #2 fix).
  - `BotPotionManager.tickPotionCheck(entry, bot)` — auto-pot.
  - `BotPotionManager.tickPassiveRecovery(entry, bot)` — passive HP/MP regen.
  - `BotCombatManager.tickSupportHealing/tickBuffs`, `BotBuffManager.tick` — buffs (runAiTick-gated, optional v1).
- Owner-COUPLED bits to SKIP for ownerless: `BotChatManager.tickAfkCheck(entry, owner)`,
  `BotPqHooks.tick(entry, bot, owner)`, follow/anchor logic.
- Fall recovery already exists: `recoverTeleportDistance(entry, bot, targetPos)`.

## Phases

### Phase 0 — Regression baseline (do FIRST)
Spawn a real owner + bot in-game (or with an owner online), confirm it grinds, record
kills/exp behavior. This is the contract Phase 1 must not change. Also run
`./mvnw test -Dtest=PlannerDecisionTest`.

### Phase 1 — Extract the grind core (pure move, no logic change)
- New method `boolean tickGrindCombat(BotEntry entry, Character bot, Point botPos, Point targetPos, boolean runAiTick, boolean perf)` containing the current 2084–2382 block.
- Convert each `return;` inside it to `return true;` (tick consumed); add `return false;`
  only if a non-consuming fall-through exists (it currently always returns).
- Replace the inline block in the owner-driven path with `if (tickGrindCombat(...)) return;`.
- **Verify owner-driven grind is byte-for-byte equivalent** (Phase 0 baseline + diff review).

### Phase 2 — Survival subset
- New method `tickOwnerlessSurvival(BotEntry entry, Character bot, boolean runAiTick)` =
  `tickActionLock` + `tickPotionCheck` + `tickPassiveRecovery` (+ buffs if runAiTick).
  Owner-independent only — no AFK/PQ.

### Phase 3 — Wire the ownerless path
- Spawn sentinel back to `0` (so `owner == null`).
- In the `owner == null` branch, when `entry.grinding` (or farmAnchor) and `hp > 0`:
  1. `tickOwnerlessSurvival(...)`
  2. fall recovery: `recoverTeleportDistance(entry, bot, ...)`
  3. map-change foothold rebuild (mirror the existing block)
  4. `tickGrindCombat(entry, bot, botPos, targetPos, runAiTick, perf)`
- Dead handling: if `hp <= 0`, a minimal null-owner respawn (or idle) — no owner deref.

### Phase 4 — Verify
- Spawn Rudy (boosted lv25): confirm via panel telemetry that it paths to mobs,
  attacks, kills, **exp climbs**, stays on map, survives (pots tick down).
- Re-run Phase 0 owner-bot baseline → zero regression.
- `./mvnw clean test-compile` + `PlannerDecisionTest`.

## Risks & mitigations
- **Touching the battle-tested grind block** (CLAUDE.md: don't break it). → Phase 1 is a
  pure extraction; verify with the owner-bot baseline + careful diff. Explain in commit.
- **Null-owner derefs** in the shared preamble. → The ownerless path runs its OWN
  sequence of owner-independent helpers; it does NOT fall through the owner-coupled
  preamble. No null-guarding of shared code required.
- **Loot/drops** for ownerless bots — out of scope for v1 (note it; bots won't pick up).

## Out of scope (follow-ups)
- Loot pickup for ownerless bots.
- Moving bots off the player's `admin` account to a dedicated bot account.
- Cross-map travel / GRIND_AT honoring a requested map.
