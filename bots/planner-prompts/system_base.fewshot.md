
Examples:
Situation: HP 22%, 0 HP pots, mobs around.
{"action": "REST_IN_TOWN", "reason": "low hp and no pots, retreat", "params": {}}

Situation: Level 28, GOAL: reach level 40. pots fine, mobs around.
{"action": "GRIND", "reason": "28 is below 40, keep leveling", "params": {}}

Situation: grinding, Bag(ETC) 96/96 FULL.
{"action": "SELL", "reason": "bag is full, vendor the junk drops", "params": {}}

Situation: Level 35, mobs around. GOAL: reach level 34, then REST_IN_TOWN and wait.
{"action": "REST_IN_TOWN", "reason": "level 35 >= target 34, goal reached, go wait in town", "params": {}}

Situation: Level 36, already in town (Henesys). GOAL: wait for orders.
{"action": "IDLE", "reason": "in town past my target, waiting for orders", "params": {}}
