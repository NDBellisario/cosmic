package server.bots;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopFactory;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BotShopManagerTest {
    @Test
    void shouldNotTriggerClawShopVisitWhenTotalStarsAreAboveThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000, 1000, 1000, 1000);
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        assertFalse(entry.shopVisitPending);
    }

    @Test
    void shouldTriggerClawShopVisitWhenTotalStarsAreBelowThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000);
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        assertTrue(entry.shopVisitPending);
    }

    @Test
    void shouldRechargeStarsWhileShoppingEvenWhenTotalStarsAreAboveTrigger() {
        Character bot = clawBotWithStars(800, 1000, 1000, 1000, 1000, 1000);

        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.CLAW));
        assertTrue(BotShopManager.shouldRechargeWhileShopping(bot, WeaponType.CLAW));
    }

    @Test
    void shouldBuyArrowsWhileShoppingWhenBelowTargetButAboveTrigger() {
        Character bot = bowBotWithArrows(4500);

        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.BOW));
        assertTrue(BotShopManager.shouldBuyFixedAmmoWhileShopping(bot, WeaponType.BOW));
    }

    @Test
    void shouldTriggerSellTrashShopVisitEvenWhenNoResupplyIsNeeded() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(new Inventory(bot, InventoryType.USE, (byte) 24));
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotInventoryManager> inventories = mockStatic(BotInventoryManager.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            inventories.when(() -> BotInventoryManager.collectSellTrashEquips(entry, bot))
                    .thenReturn(List.of(mock(Item.class)));

            BotShopManager.requestSellTrashVisit(entry, bot);
        }

        assertTrue(entry.shopVisitPending);
        assertTrue(entry.shopSellTrashPending);
        assertEquals(new Point(20, 0), entry.shopNpcPos);
    }

    private static Character clawBotWithStars(int... quantities) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        for (int quantity : quantities) {
            use.addItem(Items.itemWithQuantity(2070000, quantity));
        }
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    private static Character bowBotWithArrows(int quantity) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2060000, quantity));
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    private static boolean entryWouldTriggerShopVisit(Character bot, WeaponType weaponType) {
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.<server.ShopItem>of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(weaponType);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        return entry.shopVisitPending;
    }

    private static NPC shopNpc(Point position) {
        NPC npc = mock(NPC.class);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getId()).thenReturn(9010000);
        when(npc.getPosition()).thenReturn(new Point(position));
        return npc;
    }

    // --- equip-upgrade buy planner (ownerless gear buying) ---

    @Test
    void planBuysCheapestUpgradesFirstUnderMesoBudget() {
        // Two upgrades the optimizer wants; bot can only afford the cheaper one above reserve.
        BotShopManager.EquipUpgrade cheap = new BotShopManager.EquipUpgrade((short) 0, 1040000, 1000);
        BotShopManager.EquipUpgrade pricey = new BotShopManager.EquipUpgrade((short) 1, 1040001, 9000);

        List<BotShopManager.EquipUpgrade> plan = BotShopManager.planEquipUpgradeBuys(
                List.of(pricey, cheap), /*meso*/ 6500, /*freeSlots*/ 8, /*reserve*/ 5000);

        assertEquals(1, plan.size());
        assertEquals(cheap, plan.get(0)); // cheapest-first; pricey would break the reserve
    }

    @Test
    void planSkipsUpgradesThatWouldBreakMesoReserve() {
        BotShopManager.EquipUpgrade up = new BotShopManager.EquipUpgrade((short) 0, 1040000, 2000);
        // meso 6000, reserve 5000 -> only 1000 spendable, so the 2000-meso item is skipped.
        List<BotShopManager.EquipUpgrade> plan = BotShopManager.planEquipUpgradeBuys(
                List.of(up), 6000, 8, 5000);
        assertTrue(plan.isEmpty());
    }

    @Test
    void planRespectsFreeBagSlots() {
        BotShopManager.EquipUpgrade a = new BotShopManager.EquipUpgrade((short) 0, 1040000, 100);
        BotShopManager.EquipUpgrade b = new BotShopManager.EquipUpgrade((short) 1, 1040001, 100);
        // Plenty of meso, but only one free equip slot -> buy exactly one.
        List<BotShopManager.EquipUpgrade> plan = BotShopManager.planEquipUpgradeBuys(
                List.of(a, b), 1_000_000, /*freeSlots*/ 1, 5000);
        assertEquals(1, plan.size());
    }

    @Test
    void planBuysAtMostOnePerShopSlot() {
        // Same shop slot offered twice (anti-spam): plan should keep only one.
        BotShopManager.EquipUpgrade first = new BotShopManager.EquipUpgrade((short) 3, 1040000, 100);
        BotShopManager.EquipUpgrade dup = new BotShopManager.EquipUpgrade((short) 3, 1040000, 100);
        List<BotShopManager.EquipUpgrade> plan = BotShopManager.planEquipUpgradeBuys(
                List.of(first, dup), 1_000_000, 8, 5000);
        assertEquals(1, plan.size());
    }
}
