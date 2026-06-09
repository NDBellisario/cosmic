# Sell-Junk Plan (ownerless bots)

Branch: `bot-sell-junk` (off `ownerless-hunting`)

## Goal
When its inventory fills from grinding, an ownerless bot autonomously goes to a
town shop and sells **non-quest ETC drops + junk equips**, then returns to
hunting. SLM-driven via a new `SELL` action (decisions locked with the user:
sell ETC + junk equips; trigger = the SLM decides).

## What already exists (reuse)
- `BotShopManager.requestSellTrashVisit` + the shop-visit flow — but it sells
  EQUIP only and is triggered solely by an owner chat command ("sell trash").
- `BotInventoryManager.collectSellTrashEquips` — junk-equip filter.
- `ItemInformationProvider.isQuestItem(id)`, `isLootRestricted(id)`,
  `getPrice(id, qty)` — exactly the ETC sell filter we need.
- `server.Shop.sell(client, InventoryType, position, qty)` — the sell call
  (used today as `shop.sell(client, EQUIP, pos, (short)1)`).
- `Inventory.getNumFreeSlot()/isFull(margin)` — fullness for the prompt.

## The hard part
`findBestShop` uses `getMapObjectsInRange` — RANGE-LIMITED. And ownerless bots
have a grind-only tick path (no shop-visit, no cross-map travel). So the bot
can't just walk to a store. The clean mechanism (consistent with the
reach-timeout teleport that already fixed hunting):

**teleport directly onto a town store NPC → batch-sell → teleport back.**

## Plan

### Phase 1 — Sell logic (self-contained, testable)
- `BotInventoryManager.collectSellTrashEtc(bot)` → ETC items where
  `!isQuestItem && !isLootRestricted && getPrice>0`.
- `BotShopManager.sellJunkAtShop(entry, bot, Shop)` → batch `shop.sell` for all
  collected ETC (full stack qty) + junk equips (qty 1). Returns count/meso.

### Phase 2 — SELL planner action
- `PlannerAction.SELL`; system prompt + fewshot list it ("when your bag is full,
  SELL"); `apply()` → `BotManager.issueSellJunk(entry)`.
- `SituationBuilder.buildForPlanner`: add a `Bag: ETC u/limit, free n` line so the
  SLM can see it's full and choose SELL.

### Phase 3 — issueSellJunk + ownerless orchestration (the work)
- `issueSellJunk(entry)`: record `entry.sellReturnMapId = bot.getMapId()`.
  Resolve a town store: teleport to the hunting map's `getReturnMap()` (town),
  whole-map-scan its NPCs for one with `getShopForNPC != null` (new helper, NOT
  range-limited), teleport onto that NPC, `sellJunkAtShop`, then teleport back to
  `sellReturnMapId` and resume grind.
- Add a small "selling" state so the ownerless tick runs this sequence across a
  couple of ticks (teleport/map-change needs a tick to settle) instead of mid-grind.

### Phase 4 — Verify
- Fill Rudy's ETC bag (or lower the threshold), confirm the SLM picks SELL,
  bot teleports to a store, junk sold (meso up, ETC freed, quest items kept),
  returns to its hunting map, resumes. PlannerDecisionTest stays green.

## Risks / notes
- Need a reliable store-NPC resolution (returnMap whole-map scan; fall back to a
  hardcoded town general store if the returnMap has none).
- Quest-item safety: the filter is `isQuestItem || isLootRestricted` — never sell
  those. Worth a guard + a unit test.
- Keep owner-driven bots on their existing owner-command sell path (unchanged).
