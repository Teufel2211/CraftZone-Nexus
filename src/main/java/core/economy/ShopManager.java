package core.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import net.minecraft.item.Item;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import core.util.Safe;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import core.config.ConfigManager;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShopManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
        .setPrettyPrinting()
        .create();

    private static final File SHOP_FILE = new File("shop.json");
    private static final File SHOP_CATEGORIES_FILE = new File("shop-categories.json");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Set<String> SHOP_EXCLUDED_ITEMS = Set.of(
        "minecraft:air",
        "minecraft:cave_air",
        "minecraft:void_air",
        "minecraft:barrier",
        "minecraft:bedrock",
        "minecraft:end_portal_frame",
        "minecraft:end_portal",
        "minecraft:end_gateway",
        "minecraft:spawner",
        "minecraft:trial_spawner",
        "minecraft:vault",
        "minecraft:ominous_vault",
        "minecraft:budding_amethyst",
        "minecraft:reinforced_deepslate",
        "minecraft:petrified_oak_slab",
        "minecraft:command_block",
        "minecraft:chain_command_block",
        "minecraft:repeating_command_block",
        "minecraft:command_block_minecart",
        "minecraft:structure_block",
        "minecraft:structure_void",
        "minecraft:jigsaw",
        "minecraft:debug_stick",
        "minecraft:light",
        "minecraft:knowledge_book",
        "minecraft:test_block",
        "minecraft:test_instance_block"
    );
    private static final Set<String> ONE_PER_WORLD_ITEMS = Set.of(
        "minecraft:dragon_egg"
    );
    private static final Map<String, String> CATEGORY_OVERRIDES = Map.ofEntries(
        Map.entry("minecraft:torch", "FunctionalBlocks"),
        Map.entry("minecraft:lantern", "FunctionalBlocks"),
        Map.entry("minecraft:soul_lantern", "FunctionalBlocks"),
        Map.entry("minecraft:chain", "FunctionalBlocks"),
        Map.entry("minecraft:chest", "FunctionalBlocks"),
        Map.entry("minecraft:ender_chest", "FunctionalBlocks"),
        Map.entry("minecraft:barrel", "FunctionalBlocks"),
        Map.entry("minecraft:hopper", "RedstoneBlocks"),
        Map.entry("minecraft:trapped_chest", "RedstoneBlocks"),
        Map.entry("minecraft:repeater", "RedstoneBlocks"),
        Map.entry("minecraft:comparator", "RedstoneBlocks"),
        Map.entry("minecraft:observer", "RedstoneBlocks"),
        Map.entry("minecraft:piston", "RedstoneBlocks"),
        Map.entry("minecraft:sticky_piston", "RedstoneBlocks"),
        Map.entry("minecraft:elytra", "Utilities"),
        Map.entry("minecraft:saddle", "Utilities"),
        Map.entry("minecraft:name_tag", "Utilities"),
        Map.entry("minecraft:totem_of_undying", "Utilities")
    );

    private static final Map<String, ShopItem> shopItems = new ConcurrentHashMap<>();
    private static final Set<String> customCategories = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> CREATIVE_CATEGORY_BY_ITEM = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CREATIVE_ORDER_BY_ITEM = new ConcurrentHashMap<>();
    private static final List<String> CATEGORY_ORDER = List.of(
        "BuildingBlocks",
        "ColoredBlocks",
        "NaturalBlocks",
        "FunctionalBlocks",
        "RedstoneBlocks",
        "ToolsAndCombat",
        "FoodAndDrinks",
        "Ingredients",
        "Utilities"
    );

    public enum BuyResult {
        SUCCESS,
        NOT_FOUND,
        OUT_OF_STOCK,
        INSUFFICIENT_FUNDS,
        ERROR
    }

    public static class ShopItem {
        public String itemId;
        public double buyPrice;
        public double sellPrice;
        public int stock;
        public String category;
        public boolean categoryLocked;
        public int minPermissionLevel;

        public ShopItem(String itemId, double buyPrice, double sellPrice, int stock) {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.stock = stock;
            this.category = "General";
            this.categoryLocked = false;
            this.minPermissionLevel = 0;
        }
    }

    public static void init() {
        Safe.run("ShopManager.loadCustomCategories", ShopManager::loadCustomCategories);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ShopManager.loadShop", () -> loadShop(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ShopManager.saveShop", () -> {
            saveShop(server);
            saveCustomCategories();
        }));
        Safe.run("ShopManager.initializeDefaultShop", ShopManager::initializeDefaultShop);
    }

    private static void initializeDefaultShop() {
        sanitizeCatalog();
    }

    private static void ensureAllEligibleItemsPresent() {
        int added = 0;
        for (Identifier id : Registries.ITEM.getIds()) {
            String itemId = id.toString();
            if (!canBeSoldInShop(itemId)) continue;
            if (shopItems.containsKey(itemId)) continue;

            ShopItem generated = generateShopItem(itemId);
            shopItems.put(itemId, generated);
            added++;
        }
        if (added > 0) {
            LOGGER.info("Added {} missing items to shop catalog", added);
        }
    }

    private static void sanitizeCatalog() {
        refreshCreativeCatalogIndex();
        pruneIneligibleItems();
        recategorizeCatalog();
        ensureAllEligibleItemsPresent();
    }

    public static int recategorizeAllNow() {
        sanitizeCatalog();
        saveShop(null);
        return shopItems.size();
    }

    private static void refreshCreativeCatalogIndex() {
        CREATIVE_CATEGORY_BY_ITEM.clear();
        CREATIVE_ORDER_BY_ITEM.clear();
        int order = 0;
        try {
            Class<?> itemGroupsClass = Class.forName("net.minecraft.item.ItemGroups");
            Method getGroups = itemGroupsClass.getMethod("getGroups");
            Object groupsObj = getGroups.invoke(null);
            if (!(groupsObj instanceof List<?> groups)) return;

            for (Object group : groups) {
                if (group == null) continue;
                String category = mapCreativeGroupToCategory(group);
                if (category == null) continue;

                Collection<?> displayStacks = invokeStackCollection(group, "getDisplayStacks");
                if (displayStacks == null || displayStacks.isEmpty()) {
                    displayStacks = invokeStackCollection(group, "getSearchTabStacks");
                }
                if (displayStacks == null) continue;

                for (Object obj : displayStacks) {
                    if (!(obj instanceof ItemStack stack) || stack.isEmpty()) continue;
                    String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                    CREATIVE_CATEGORY_BY_ITEM.putIfAbsent(itemId, category);
                    CREATIVE_ORDER_BY_ITEM.putIfAbsent(itemId, order++);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read creative tab order, fallback categorization will be used", e);
        }
    }

    private static Collection<?> invokeStackCollection(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            if (result instanceof Collection<?> collection) {
                return collection;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String mapCreativeGroupToCategory(Object group) {
        String byId = mapCreativeGroupIdToCategory(group);
        if (byId != null) return byId;
        try {
            Method getDisplayName = group.getClass().getMethod("getDisplayName");
            Object textObj = getDisplayName.invoke(group);
            String name = textObj instanceof Text text
                ? text.getString().toLowerCase(Locale.ROOT)
                : String.valueOf(textObj).toLowerCase(Locale.ROOT);

            if (name.contains("building")) return "BuildingBlocks";
            if (name.contains("colored")) return "ColoredBlocks";
            if (name.contains("natural")) return "NaturalBlocks";
            if (name.contains("functional")) return "FunctionalBlocks";
            if (name.contains("redstone")) return "RedstoneBlocks";
            if (name.contains("combat") || name.contains("tool")) return "ToolsAndCombat";
            if (name.contains("food")) return "FoodAndDrinks";
            if (name.contains("ingredient")) return "Ingredients";
            if (name.contains("spawn")) return "SpawnEggs";
            if (name.contains("operator") || name.contains("inventory")) return null;
            return "Utilities";
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String mapCreativeGroupIdToCategory(Object group) {
        try {
            Method getId = group.getClass().getMethod("getId");
            Object idObj = getId.invoke(group);
            String id = String.valueOf(idObj).toLowerCase(Locale.ROOT);
            if (id.contains("operator") || id.contains("inventory")) return null;
            if (id.contains("building")) return "BuildingBlocks";
            if (id.contains("colored")) return "ColoredBlocks";
            if (id.contains("natural")) return "NaturalBlocks";
            if (id.contains("functional")) return "FunctionalBlocks";
            if (id.contains("redstone")) return "RedstoneBlocks";
            if (id.contains("combat") || id.contains("tools")) return "ToolsAndCombat";
            if (id.contains("food")) return "FoodAndDrinks";
            if (id.contains("ingredients")) return "Ingredients";
            if (id.contains("spawn")) return "SpawnEggs";
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean canBeSoldInShop(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        if (SHOP_EXCLUDED_ITEMS.contains(itemId)) return false;
        if (isOnePerWorldItem(itemId)) return false;
        String lower = itemId.toLowerCase(Locale.ROOT);
        if (lower.contains("command_block") || lower.contains("structure_block")) return false;
        if (lower.contains("jigsaw") || lower.contains("debug")) return false;
        if (lower.contains("test_")) return false;
        if (lower.contains("spawn_egg")) return false;
        if (lower.contains("portal") || lower.contains("gateway")) return false;
        if (lower.contains("barrier") || lower.contains("bedrock")) return false;
        if (lower.contains("spawner") || lower.contains("trial_spawner")) return false;
        if (lower.contains("reinforced_deepslate") || lower.contains("budding_amethyst")) return false;
        if (lower.contains("petrified_oak_slab") || lower.contains("knowledge_book") || lower.contains("light")) return false;
        return Registries.ITEM.containsId(Identifier.of(itemId));
    }

    public static boolean isOnePerWorldItem(String itemId) {
        return itemId != null && ONE_PER_WORLD_ITEMS.contains(itemId);
    }

    private static void pruneIneligibleItems() {
        int before = shopItems.size();
        shopItems.entrySet().removeIf(e -> !canBeSoldInShop(e.getKey()));
        int removed = before - shopItems.size();
        if (removed > 0) {
            LOGGER.info("Removed {} ineligible items from shop catalog", removed);
        }
    }

    private static void recategorizeCatalog() {
        for (ShopItem item : shopItems.values()) {
            if (item == null || item.itemId == null) continue;
            if (!Registries.ITEM.containsId(Identifier.of(item.itemId))) continue;
            if (item.categoryLocked && item.category != null && !item.category.isBlank()) {
                item.category = normalizeCategory(item.category);
                continue;
            }
            int maxCount = Math.max(1, Registries.ITEM.get(Identifier.of(item.itemId)).getMaxCount());
            item.category = normalizeCategory(guessCategory(item.itemId, maxCount));
        }
    }

    private static ShopItem generateShopItem(String itemId) {
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        int maxCount = Math.max(1, item.getMaxCount());
        double buy = estimateBuyPrice(itemId, maxCount);
        double sell = Math.max(0.01, round2(buy * 0.70));
        ShopItem entry = new ShopItem(itemId, buy, sell, -1);
        entry.category = normalizeCategory(guessCategory(itemId, maxCount));
        entry.minPermissionLevel = 0;
        return entry;
    }

    private static double estimateBuyPrice(String itemId, int maxCount) {
        String id = itemId.toLowerCase(Locale.ROOT);
        if (id.contains("netherite")) return 1800.0;
        if (id.contains("elytra")) return 2500.0;
        if (id.contains("totem")) return 1200.0;
        if (id.contains("beacon")) return 2000.0;
        if (id.contains("dragon_egg")) return 5000.0;
        if (id.contains("diamond")) return 250.0;
        if (id.contains("emerald")) return 180.0;
        if (id.contains("gold")) return 40.0;
        if (id.contains("iron")) return 16.0;
        if (id.contains("spawn_egg")) return 750.0;
        if (maxCount == 1) return 120.0;
        if (maxCount <= 16) return 36.0;
        return 12.0;
    }

    private static String guessCategory(String itemId, int maxCount) {
        String override = CATEGORY_OVERRIDES.get(itemId);
        if (override != null) return override;

        String creativeCategory = CREATIVE_CATEGORY_BY_ITEM.get(itemId);
        if (creativeCategory != null) return creativeCategory;

        String id = itemId.toLowerCase(Locale.ROOT);
        if (id.contains("spawn_egg")) return "SpawnEggs";

        if (id.contains("sword") || id.contains("axe") || id.contains("pickaxe") || id.contains("shovel")
            || id.contains("hoe") || id.contains("bow") || id.contains("crossbow") || id.contains("trident")
            || id.contains("shield") || id.contains("mace")
            || id.contains("helmet") || id.contains("chestplate") || id.contains("leggings") || id.contains("boots")) {
            return "ToolsAndCombat";
        }

        if (id.contains("apple") || id.contains("bread") || id.contains("stew") || id.contains("beef")
            || id.contains("chicken") || id.contains("pork") || id.contains("fish") || id.contains("carrot")
            || id.contains("potato") || id.contains("melon_slice") || id.contains("cookie")
            || id.contains("pumpkin_pie") || id.contains("golden_apple") || id.contains("honey_bottle")
            || id.contains("suspicious_stew")) {
            return "FoodAndDrinks";
        }

        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("observer")
            || id.contains("piston") || id.contains("hopper") || id.contains("target") || id.contains("daylight_detector")
            || id.contains("lectern") || id.contains("tripwire") || id.contains("lever") || id.contains("button")
            || id.contains("pressure_plate") || id.contains("sculk_sensor")) {
            return "RedstoneBlocks";
        }

        if (id.contains("bed") || id.contains("banner") || id.contains("wool") || id.contains("carpet")
            || id.contains("terracotta") || id.contains("concrete") || id.contains("stained_glass")
            || id.contains("glazed_terracotta")) {
            return "ColoredBlocks";
        }

        if (id.contains("sapling") || id.contains("leaves") || id.contains("log") || id.contains("wood")
            || id.contains("mushroom") || id.contains("flower") || id.contains("grass") || id.contains("dirt")
            || id.contains("sand") || id.contains("gravel") || id.contains("ice") || id.contains("snow")
            || id.contains("coral") || id.contains("clay") || id.contains("ore") || id.contains("stone")
            || id.contains("deepslate") || id.contains("netherrack") || id.contains("end_stone") || id.contains("obsidian")) {
            return "NaturalBlocks";
        }

        if (id.contains("crafting_table") || id.contains("furnace") || id.contains("anvil") || id.contains("smithing")
            || id.contains("cartography") || id.contains("loom") || id.contains("stonecutter") || id.contains("grindstone")
            || id.contains("enchanting_table") || id.contains("brewing_stand") || id.contains("barrel")
            || id.contains("chest") || id.contains("shulker_box") || id.contains("beacon") || id.contains("respawn_anchor")
            || id.contains("lodestone")) {
            return "FunctionalBlocks";
        }

        if (id.contains("ingot") || id.contains("nugget") || id.contains("gem") || id.contains("dust")
            || id.contains("shard") || id.contains("crystal") || id.contains("rod") || id.contains("string")
            || id.contains("flint") || id.contains("feather") || id.contains("leather") || id.contains("paper")
            || id.contains("book") || id.contains("bottle") || id.contains("dye") || id.contains("seed")
            || id.contains("wart") || id.contains("slime_ball") || id.contains("blaze_rod")
            || id.contains("ender_pearl")) {
            return "Ingredients";
        }

        if (id.contains("bucket") || id.contains("boat") || id.contains("minecart") || id.contains("compass")
            || id.contains("clock") || id.contains("lead") || id.contains("elytra") || id.contains("totem")
            || id.contains("firework") || id.contains("name_tag") || id.contains("saddle")) {
            return "Utilities";
        }

        if (maxCount == 64 || id.endsWith("_slab") || id.endsWith("_stairs") || id.endsWith("_wall")) {
            return "BuildingBlocks";
        }
        return "Utilities";
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return "Utilities";
        String key = category.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "general", "misc", "materials", "utility" -> "Utilities";
            case "blocks", "building" -> "BuildingBlocks";
            case "colored" -> "ColoredBlocks";
            case "natural" -> "NaturalBlocks";
            case "functional" -> "FunctionalBlocks";
            case "redstone" -> "RedstoneBlocks";
            case "tools", "combat", "armor" -> "ToolsAndCombat";
            case "food" -> "FoodAndDrinks";
            default -> category;
        };
    }

    public static int getCreativeOrder(String itemId) {
        if (itemId == null) return Integer.MAX_VALUE;
        return CREATIVE_ORDER_BY_ITEM.getOrDefault(itemId, Integer.MAX_VALUE);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void normalizeItem(ShopItem item) {
        if (item == null) return;
        item.category = normalizeCategory(item.category);
        if (item.minPermissionLevel < 0) item.minPermissionLevel = 0;
        if (item.minPermissionLevel > 4) item.minPermissionLevel = 4;
    }

    public static boolean buyItem(UUID playerId, String itemId, int quantity) {
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        if (item.stock >= 0 && item.stock < quantity) return false;

        double totalCost = item.buyPrice * quantity;
        if (EconomyManager.getBalance(playerId).doubleValue() < totalCost) return false;

        if (EconomyManager.chargePlayer(playerId, BigDecimal.valueOf(totalCost), EconomyManager.TransactionType.SHOP_BUY,
                "Bought " + quantity + "x " + itemId)) {
            if (item.stock >= 0) item.stock -= quantity;
            saveShop(null);
            return true;
        }
        return false;
    }

    public static BuyResult buyAndDeliver(ServerPlayerEntity player, String itemId, int quantity) {
        try {
            if (player == null || itemId == null || quantity <= 0) return BuyResult.ERROR;
            ShopItem item = shopItems.get(itemId);
            normalizeItem(item);
            if (item == null) return BuyResult.NOT_FOUND;
            if (item.stock >= 0 && item.stock < quantity) return BuyResult.OUT_OF_STOCK;
            if (!hasMinPermissionLevel(player, item.minPermissionLevel)) return BuyResult.ERROR;

            BigDecimal totalCost = BigDecimal.valueOf(item.buyPrice).multiply(BigDecimal.valueOf(quantity));
            if (EconomyManager.getBalance(player.getUuid()).compareTo(totalCost) < 0) return BuyResult.INSUFFICIENT_FUNDS;

            boolean charged = EconomyManager.chargePlayer(player.getUuid(), totalCost, EconomyManager.TransactionType.SHOP_BUY,
                "Bought " + quantity + "x " + itemId);
            if (!charged) return BuyResult.INSUFFICIENT_FUNDS;

            if (item.stock >= 0) item.stock -= quantity;

            Item mcItem = Registries.ITEM.get(Identifier.of(itemId));
            int max = mcItem.getMaxCount();
            int remaining = quantity;
            while (remaining > 0) {
                int give = Math.min(remaining, Math.max(1, max));
                ItemStack stack = new ItemStack(mcItem, give);
                boolean inserted = player.getInventory().insertStack(stack);
                if (!inserted && !stack.isEmpty()) {
                    player.dropItem(stack, false);
                }
                remaining -= give;
            }

            saveShop(null);
            return BuyResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Shop purchase failed for {}", itemId, e);
            return BuyResult.ERROR;
        }
    }

    private static boolean hasMinPermissionLevel(ServerPlayerEntity player, int minLevel) {
        if (player == null) return false;
        PermissionLevel permLevel = switch (minLevel) {
            case 0 -> PermissionLevel.ALL;
            case 1 -> PermissionLevel.MODERATORS;
            case 2 -> PermissionLevel.ADMINS;
            default -> PermissionLevel.OWNERS;
        };
        return player.getCommandSource().getPermissions().hasPermission(new Permission.Level(permLevel));
    }

    public static double sellItem(UUID playerId, ItemStack itemStack) {
        String itemId = Registries.ITEM.getId(itemStack.getItem()).toString();
        ShopItem shopItem = shopItems.get(itemId);
        normalizeItem(shopItem);
        if (shopItem == null) return 0.0;

        double price = shopItem.sellPrice * itemStack.getCount();
        EconomyManager.rewardPlayer(playerId, BigDecimal.valueOf(price), EconomyManager.TransactionType.SHOP_SELL,
            "Sold " + itemStack.getCount() + "x " + itemId);

        if (shopItem.stock >= 0) {
            shopItem.stock += itemStack.getCount();
        }
        saveShop(null);
        return price;
    }

    public static Map<String, ShopItem> getShopItems() {
        Map<String, ShopItem> copy = new HashMap<>(shopItems);
        for (ShopItem item : copy.values()) {
            normalizeItem(item);
        }
        return copy;
    }

    public static ShopItem getShopItem(String itemId) {
        if (itemId == null) return null;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        return item;
    }

    public enum UpsertResult { SUCCESS, INVALID_ITEM, INVALID_PRICE, ERROR }

    public static UpsertResult upsertItem(String itemId, double buyPrice, double sellPrice, int stock) {
        return upsertItem(itemId, buyPrice, sellPrice, stock, "General", 0);
    }

    public static UpsertResult upsertItem(String itemId, double buyPrice, double sellPrice, int stock, String category, int minPermissionLevel) {
        try {
            if (itemId == null || itemId.isBlank()) return UpsertResult.INVALID_ITEM;
            if (buyPrice < 0 || sellPrice < 0) return UpsertResult.INVALID_PRICE;
            if (!canBeSoldInShop(itemId)) return UpsertResult.INVALID_ITEM;

            Identifier id = Identifier.of(itemId);
            if (!Registries.ITEM.containsId(id)) return UpsertResult.INVALID_ITEM;

            ShopItem item = new ShopItem(itemId, buyPrice, sellPrice, stock);
            item.category = normalizeCategory(category);
            item.minPermissionLevel = minPermissionLevel;
            normalizeItem(item);
            shopItems.put(itemId, item);
            saveShop(null);
            return UpsertResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Failed to upsert shop item {}", itemId, e);
            return UpsertResult.ERROR;
        }
    }

    public static UpsertResult updatePrices(String itemId, double buyPrice, double sellPrice) {
        if (itemId == null) return UpsertResult.INVALID_ITEM;
        if (buyPrice < 0 || sellPrice < 0) return UpsertResult.INVALID_PRICE;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return UpsertResult.INVALID_ITEM;
        item.buyPrice = buyPrice;
        item.sellPrice = sellPrice;
        saveShop(null);
        return UpsertResult.SUCCESS;
    }

    public static boolean updateCategory(String itemId, String category) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        String normalized = normalizeCategory(category);
        item.category = normalized;
        item.categoryLocked = true;
        if (!CATEGORY_ORDER.contains(normalized)) {
            customCategories.add(normalized);
            saveCustomCategories();
        }
        normalizeItem(item);
        saveShop(null);
        return true;
    }

    public static boolean unlockCategory(String itemId) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.categoryLocked = false;
        saveShop(null);
        return true;
    }

    public static boolean updateMinPermissionLevel(String itemId, int level) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.minPermissionLevel = level;
        normalizeItem(item);
        saveShop(null);
        return true;
    }

    public static boolean updateStock(String itemId, int stock) {
        if (itemId == null) return false;
        ShopItem item = shopItems.get(itemId);
        normalizeItem(item);
        if (item == null) return false;
        item.stock = stock;
        saveShop(null);
        return true;
    }

    public static boolean removeItem(String itemId) {
        if (itemId == null) return false;
        ShopItem removed = shopItems.remove(itemId);
        if (removed != null) {
            saveShop(null);
            return true;
        }
        return false;
    }

    public static void loadShop(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                if (!SHOP_FILE.exists()) {
                    saveShop(null);
                    return;
                }

                try (FileReader reader = new FileReader(SHOP_FILE)) {
                    Map<String, ShopItem> loadedItems = GSON.fromJson(reader, new TypeToken<Map<String, ShopItem>>(){}.getType());
                    if (loadedItems != null) {
                        for (ShopItem item : loadedItems.values()) {
                            normalizeItem(item);
                        }
                        shopItems.putAll(loadedItems);
                    }
                    sanitizeCatalog();
                    LOGGER.info("Loaded {} shop items", shopItems.size());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load shop data", e);
            }
        });
    }

    public static void saveShop(MinecraftServer server) {
        EXECUTOR.submit(() -> {
            try {
                try (FileWriter writer = new FileWriter(SHOP_FILE)) {
                    GSON.toJson(shopItems, writer);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to save shop data", e);
            }
        });
    }

    public static List<String> getCategories() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(customCategories);
        shopItems.values().stream()
            .peek(ShopManager::normalizeItem)
            .map(item -> item.category)
            .distinct()
            .forEach(all::add);
        return all.stream()
            .sorted((a, b) -> {
                int ia = CATEGORY_ORDER.indexOf(a);
                int ib = CATEGORY_ORDER.indexOf(b);
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                if (ia != ib) return Integer.compare(ia, ib);
                return String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .toList();
    }

    public static boolean addCustomCategory(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = normalizeCategory(name);
        if (normalized.isBlank()) return false;
        boolean added = customCategories.add(normalized);
        if (added) saveCustomCategories();
        return added;
    }

    public static boolean removeCustomCategory(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = normalizeCategory(name);
        boolean removed = customCategories.remove(normalized);
        if (removed) saveCustomCategories();
        return removed;
    }

    public static List<String> getCustomCategories() {
        return customCategories.stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static void loadCustomCategories() {
        if (!SHOP_CATEGORIES_FILE.exists()) return;
        try (FileReader reader = new FileReader(SHOP_CATEGORIES_FILE)) {
            Type type = new TypeToken<List<String>>(){}.getType();
            List<String> loaded = GSON.fromJson(reader, type);
            customCategories.clear();
            if (loaded != null) {
                for (String value : loaded) {
                    if (value != null && !value.isBlank()) {
                        customCategories.add(normalizeCategory(value));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load shop categories", e);
        }
    }

    private static void saveCustomCategories() {
        try (FileWriter writer = new FileWriter(SHOP_CATEGORIES_FILE)) {
            GSON.toJson(getCustomCategories(), writer);
        } catch (Exception e) {
            LOGGER.error("Failed to save shop categories", e);
        }
    }

    public static List<String> getNonSurvivalItemsInCatalog() {
        return shopItems.keySet().stream()
            .filter(itemId -> !canBeSoldInShop(itemId))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public static List<String> getOnePerWorldItemsInCatalog() {
        return shopItems.keySet().stream()
            .filter(ShopManager::isOnePerWorldItem)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    // Local adapter to avoid class init coupling with EconomyManager
    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(Registries.ITEM.getId(src.getItem()) + ":" + src.getCount());
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String raw = json.getAsString();
            int lastColon = raw.lastIndexOf(':');
            if (lastColon <= 0 || lastColon >= raw.length() - 1) return ItemStack.EMPTY;

            try {
                Identifier id = Identifier.of(raw.substring(0, lastColon));
                int count = Integer.parseInt(raw.substring(lastColon + 1));
                return new ItemStack(Registries.ITEM.get(id), count);
            } catch (Exception e) {
                LOGGER.warn("Failed to deserialize ItemStack: {}", raw);
                return ItemStack.EMPTY;
            }
        }
    }
}
