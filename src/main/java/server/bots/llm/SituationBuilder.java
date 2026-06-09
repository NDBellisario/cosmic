package server.bots.llm;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.game.ExpTable;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.ItemInformationProvider;
import server.StatEffect;
import server.bots.BotEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.LinkedHashMap;

/**
 * Builds a short "current situation" snapshot inlined into the LLM prompt so
 * bots can answer where/what/who questions accurately instead of hallucinating.
 * Queried fresh at every call — never cached. All reads are best-effort; any
 * null/missing piece is silently skipped so a partial state still produces a
 * useful block.
 */
public final class SituationBuilder {
    private SituationBuilder() {}

    public static String build(BotEntry entry) {
        if (entry == null || entry.getBot() == null) return "";
        Character bot = entry.getBot();
        MapleMap map = bot.getMap();
        StringBuilder sb = new StringBuilder(256);
        sb.append("[Where you are now]\n");

        if (map != null) {
            String name = map.getMapName();
            String street = map.getStreetName();
            if (name != null && !name.isBlank()) {
                sb.append("Map: ").append(name);
                if (street != null && !street.isBlank() && !street.equalsIgnoreCase(name)) {
                    sb.append(" (").append(street).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("Status: ").append(describeActivity(entry)).append('\n');

        int lvl = bot.getLevel();
        int pct = expPercent(bot, lvl);
        sb.append("Level ").append(lvl);
        if (pct >= 0) sb.append(", ").append(pct).append("% to next");
        sb.append('\n');

        String mobs = describeMobs(map);
        if (!mobs.isEmpty()) sb.append("Mobs around: ").append(mobs).append('\n');

        String party = describeParty(bot);
        if (!party.isEmpty()) sb.append("Party: ").append(party).append('\n');

        if (entry.lastOwnerCommand != null && !entry.lastOwnerCommand.isBlank()) {
            sb.append("Last command from owner: \"").append(entry.lastOwnerCommand).append('"')
                    .append(" (").append(ago(System.currentTimeMillis() - entry.lastOwnerCommandAtMs))
                    .append(" ago)\n");
        }
        return sb.toString();
    }

    /**
     * Planner-facing snapshot. Like {@link #build} but adds an inventory line
     * (HP/MP pots, meso) the decision layer needs, and drops the chat-only
     * framing. Kept terse — target is well under ~500 tokens so the SLM context
     * budget stays free for SOUL.md + GOAL.md. Every read is best-effort.
     */
    public static String buildForPlanner(BotEntry entry) {
        if (entry == null || entry.getBot() == null) return "";
        Character bot = entry.getBot();
        MapleMap map = bot.getMap();
        StringBuilder sb = new StringBuilder(320);
        sb.append("[Current situation]\n");

        if (map != null) {
            String name = map.getMapName();
            if (name != null && !name.isBlank()) {
                sb.append("Map: ").append(name).append(" (id ").append(map.getId()).append(")\n");
            } else {
                sb.append("Map id: ").append(map.getId()).append('\n');
            }
        }

        sb.append("Status: ").append(describeActivity(entry)).append('\n');

        int lvl = bot.getLevel();
        int pct = expPercent(bot, lvl);
        sb.append("Level ").append(lvl);
        if (pct >= 0) sb.append(", ").append(pct).append("% to next");
        sb.append('\n');

        try {
            int curHp = bot.getHp();
            int maxHp = bot.getCurrentMaxHp();
            if (maxHp > 0) {
                sb.append("HP: ").append((int) (100L * curHp / maxHp)).append("%\n");
            }
        } catch (Throwable ignored) {}

        sb.append(describeInventory(bot)).append('\n');

        String mobs = describeMobs(map);
        if (!mobs.isEmpty()) sb.append("Mobs around: ").append(mobs).append('\n');

        String party = describeParty(bot);
        if (!party.isEmpty()) sb.append("Party: ").append(party).append('\n');

        if (entry.lastOwnerCommand != null && !entry.lastOwnerCommand.isBlank()) {
            sb.append("Last owner command: \"").append(entry.lastOwnerCommand).append('"')
                    .append(" (").append(ago(System.currentTimeMillis() - entry.lastOwnerCommandAtMs))
                    .append(" ago)\n");
        }
        return sb.toString();
    }

    /** "Pots: 240 HP, 80 MP | Meso: 1.2m" — recovery-potion counts plus meso balance. */
    private static String describeInventory(Character bot) {
        int hp = 0;
        int mp = 0;
        try {
            ItemInformationProvider iip = ItemInformationProvider.getInstance();
            for (Item item : bot.getInventory(InventoryType.USE).list()) {
                if (item == null || item.getQuantity() <= 0) continue;
                StatEffect fx = iip.getItemEffect(item.getItemId());
                if (fx == null || !fx.getStatups().isEmpty()) continue;
                boolean healsHp = fx.getHp() > 0 || fx.getHpRate() > 0;
                boolean healsMp = fx.getMp() > 0 || fx.getMpRate() > 0;
                if (healsHp) hp += item.getQuantity();
                if (healsMp) mp += item.getQuantity();
            }
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder(48);
        sb.append("Pots: ").append(hp).append(" HP, ").append(mp).append(" MP");
        try {
            sb.append(" | Meso: ").append(compactMeso(bot.getMeso()));
        } catch (Throwable ignored) {}
        try {
            client.inventory.Inventory etc = bot.getInventory(InventoryType.ETC);
            int free = etc.getNumFreeSlot();
            sb.append(" | Bag(ETC): ").append(etc.getSlotLimit() - free).append('/').append(etc.getSlotLimit());
            if (free <= 2) sb.append(" FULL");
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String compactMeso(long meso) {
        if (meso >= 1_000_000) return String.format("%.1fm", meso / 1_000_000.0);
        if (meso >= 1_000) return String.format("%.0fk", meso / 1_000.0);
        return Long.toString(meso);
    }

    private static String describeActivity(BotEntry entry) {
        if (entry.isGrinding()) {
            MapleMap m = entry.getBot().getMap();
            if (entry.getFarmAnchor() != null && m != null && entry.getFarmAnchorMapId() == m.getId()) {
                return "grinding (camping this spot)";
            }
            return "grinding";
        }
        if (entry.isFollowing()) return "following owner";
        return "standing around, no orders";
    }

    private static int expPercent(Character bot, int lvl) {
        try {
            if (lvl >= 200) return -1;
            int needed = ExpTable.getExpNeededForLevel(lvl);
            if (needed <= 0) return -1;
            long pct = (100L * Math.max(0, bot.getExp())) / needed;
            return (int) Math.min(99, pct);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String describeMobs(MapleMap map) {
        if (map == null) return "";
        // Preserve discovery order so the same map produces stable text.
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        try {
            for (Monster mob : map.getAllMonsters()) {
                if (mob == null || !mob.isAlive()) continue;
                String key = mob.getName() + "|" + mob.getLevel();
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
            }
        } catch (Throwable t) {
            return "";
        }
        if (counts.isEmpty()) return "";
        // Top 4 by count
        record Entry(String name, int lvl, int count) {}
        java.util.List<Entry> list = new java.util.ArrayList<>(counts.size());
        for (var e : counts.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            int lvl = 0;
            try { lvl = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            list.add(new Entry(parts[0], lvl, e.getValue()[0]));
        }
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Entry e : list) {
            if (shown == 4) { sb.append(", ..."); break; }
            if (shown > 0) sb.append(", ");
            sb.append(e.name()).append(" lv").append(e.lvl()).append(" x").append(e.count());
            shown++;
        }
        return sb.toString();
    }

    private static String describeParty(Character bot) {
        Party party = bot.getParty();
        if (party == null) return "";
        int myMapId = bot.getMapId();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PartyCharacter m : party.getMembers()) {
            if (m == null || m.getId() == bot.getId()) continue;
            if (!first) sb.append(", ");
            sb.append(m.getName());
            if (m.isLeader()) sb.append(" (leader)");
            if (m.getMapId() != myMapId) sb.append(" (elsewhere)");
            first = false;
        }
        return sb.toString();
    }

    /** Compact relative-time string: "12s", "4m", "2h", "3d". */
    public static String ago(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long s = deltaMs / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }
}
