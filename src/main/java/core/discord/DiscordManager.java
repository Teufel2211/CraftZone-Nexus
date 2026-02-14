package core.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import core.config.ConfigManager;
import core.bounty.BountyManager;
import core.economy.EconomyManager;
import core.util.Safe;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DiscordManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-discord-logger");
        t.setDaemon(true);
        return t;
    });
    private static final File LINKED_ACCOUNTS_FILE = new File("data/core-discord-links.json");
    private static final int DISCORD_MAX_EMBED_DESCRIPTION = 3500;
    private static final int WEBHOOK_MAX_RETRIES = 3;
    private static final long WEBHOOK_RETRY_DELAY_MS = 400L;
    private static final long DEDUPE_WINDOW_MS = 1500L;

    private static JDA jda;
    private static volatile boolean botStarted = false;
    private static final Map<String, UUID> discordToMinecraft = new ConcurrentHashMap<>();
    private static final Map<UUID, String> minecraftToDiscord = new ConcurrentHashMap<>();
    private static final Map<String, Long> webhookDedupe = new ConcurrentHashMap<>();
    private static MinecraftServer server;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> Safe.run("DiscordManager.onServerStarted", () -> {
            server = s;
            startBotIfConfigured();
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("DiscordManager.shutdown", DiscordManager::shutdown));
        Safe.run("DiscordManager.loadLinkedAccounts", DiscordManager::loadLinkedAccounts);
    }

    private static void startBotIfConfigured() {
        if (botStarted) return;
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.discord == null) return;

        String token = cfg.discord.botToken;
        if (token == null || token.isBlank()) {
            LOGGER.info("Discord bot disabled: botToken is empty.");
            return;
        }

        botStarted = true;
        EXECUTOR.submit(() -> {
            try {
                jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordCommandListener())
                    .build();

                jda.awaitReady();

                if (cfg.discord.enableBidirectionalCommands) {
                    registerSlashCommands();
                }

                LOGGER.info("Discord bot started with server.");
            } catch (Exception e) {
                botStarted = false;
                LOGGER.error("Failed to initialize Discord bot", e);
            }
        });
    }

    private static void registerSlashCommands() {
        if (jda == null) return;

        Guild guild = jda.getGuildById(ConfigManager.getConfig().discord.serverId);
        if (guild == null) return;

        guild.updateCommands().addCommands(
            Commands.slash("ban", "Ban a player")
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.STRING, "reason", "Ban reason", false),
            Commands.slash("eco", "Economy commands")
                .addOption(OptionType.STRING, "action", "Action (set/add/remove)", true)
                .addOption(OptionType.STRING, "player", "Player name", true)
                .addOption(OptionType.NUMBER, "amount", "Amount", true),
            Commands.slash("bounty", "Bounty commands")
                .addOption(OptionType.STRING, "action", "Action (place/list)", true)
                .addOption(OptionType.STRING, "player", "Target player", false)
                .addOption(OptionType.NUMBER, "amount", "Bounty amount", false)
        ).queue(
            ignored -> LOGGER.info("Discord slash commands registered"),
            error -> LOGGER.warn("Failed to register discord slash commands", error)
        );
    }

    // Account Linking
    public static boolean linkAccount(String discordId, UUID minecraftUuid) {
        if (discordToMinecraft.containsKey(discordId) || minecraftToDiscord.containsKey(minecraftUuid)) {
            return false; // Already linked
        }

        discordToMinecraft.put(discordId, minecraftUuid);
        minecraftToDiscord.put(minecraftUuid, discordId);
        saveLinkedAccounts();
        return true;
    }

    public static boolean unlinkAccount(String discordId, UUID minecraftUuid) {
        if (!discordToMinecraft.getOrDefault(discordId, UUID.randomUUID()).equals(minecraftUuid)) {
            return false;
        }

        discordToMinecraft.remove(discordId);
        minecraftToDiscord.remove(minecraftUuid);
        saveLinkedAccounts();
        return true;
    }

    public static UUID getMinecraftUuid(String discordId) {
        return discordToMinecraft.get(discordId);
    }

    public static String getDiscordId(UUID minecraftUuid) {
        return minecraftToDiscord.get(minecraftUuid);
    }

    public static boolean isLinked(String discordId) {
        return discordToMinecraft.containsKey(discordId);
    }

    public static boolean isLinked(UUID minecraftUuid) {
        return minecraftToDiscord.containsKey(minecraftUuid);
    }

    // Enhanced Webhook Logging
    public static void sendEconomyLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.economyLogWebhook, "💰 Economy Transaction", message, 0x00FF00);
    }

    public static void sendAdminActionLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.adminActionsWebhook, "👑 Admin Action", message, 0xFF0000);
    }

    public static void sendBountyFeed(String message) {
        sendWebhook(ConfigManager.getConfig().discord.bountyFeedWebhook, "🎯 Bounty Update", message, 0xFFA500);
    }

    public static void sendAnticheatAlert(String message, boolean critical) {
        int color = critical ? 0xFF0000 : 0xFFFF00;
        String title = critical ? "🚨 CRITICAL Anti-Cheat Alert" : "⚠️ Anti-Cheat Alert";
        sendWebhook(ConfigManager.getConfig().discord.anticheatAlertsWebhook, title, message, color);
    }

    public static void sendClanEvent(String message) {
        sendWebhook(ConfigManager.getConfig().discord.clanEventsWebhook, "🏰 Clan Event", message, 0x800080);
    }

    public static void sendPlayerReport(String message) {
        sendWebhook(ConfigManager.getConfig().discord.playerReportsWebhook, "📝 Player Report", message, 0xFF69B4);
    }

    // Legacy methods for backward compatibility
    public static void sendOPLog(String message) {
        sendAdminActionLog(message);
    }

    public static void sendMessageLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.messageLogWebhook, "💬 Private Message", message, 0x0080FF);
    }

    public static void sendChatLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.messageLogWebhook, "🗣️ Public Chat", message, 0x0080FF);
    }

    public static void sendCommandLog(String message) {
        sendWebhook(ConfigManager.getConfig().discord.commandLogWebhook, "⚡ Command Executed", message, 0x808080);
    }

    private static void sendWebhook(String url, String title, String description, int color) {
        String webhookUrl = resolveWebhook(url);
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        EXECUTOR.submit(() -> {
            String safeDescription = sanitizeDiscordText(description);
            if (isDuplicate(webhookUrl, title, safeDescription)) {
                return;
            }
            List<Field> fields = extractFields(safeDescription);
            String embedDescription = fields.isEmpty()
                ? formatDescription(safeDescription)
                : "Details";
            Embed embed = new Embed(title, embedDescription, color, fields);
            String json = GSON.toJson(new WebhookPayload(embed));
            RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
            Request request = new Request.Builder()
                .url(webhookUrl)
                .header("User-Agent", "core-mod-discord-logger")
                .post(body)
                .build();

            for (int attempt = 1; attempt <= WEBHOOK_MAX_RETRIES; attempt++) {
                try (Response response = CLIENT.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        return;
                    }
                    LOGGER.warn("Discord webhook failed (attempt {}): HTTP {}", attempt, response.code());
                } catch (IOException e) {
                    LOGGER.warn("Discord webhook IO error (attempt {})", attempt, e);
                }

                if (attempt < WEBHOOK_MAX_RETRIES) {
                    try {
                        Thread.sleep(WEBHOOK_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    private static boolean isDuplicate(String webhookUrl, String title, String description) {
        String key = webhookUrl + "|" + title + "|" + description;
        long now = System.currentTimeMillis();
        Long prev = webhookDedupe.put(key, now);
        if (prev == null) return false;
        return now - prev < DEDUPE_WINDOW_MS;
    }

    private static String formatDescription(String raw) {
        if (raw == null || raw.isBlank()) return "(empty)";
        String[] lines = raw.split("\\R");
        if (lines.length <= 1) return raw;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.isBlank()) {
                sb.append("• ").append(line.trim()).append('\n');
            }
        }
        String formatted = sb.toString().trim();
        if (formatted.isEmpty()) return raw;
        return formatted;
    }

    private static List<Field> extractFields(String raw) {
        List<Field> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx >= trimmed.length() - 1) continue;
            String name = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (name.length() > 64) name = name.substring(0, 64);
            if (value.length() > 256) value = value.substring(0, 256);
            if (!name.isEmpty() && !value.isEmpty()) {
                out.add(new Field(name, value, true));
            }
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static String resolveWebhook(String specificWebhook) {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.discord == null) return null;
        if (specificWebhook != null && !specificWebhook.isBlank()) return specificWebhook;
        return cfg.discord.webhookUrl;
    }

    private static String sanitizeDiscordText(String raw) {
        if (raw == null) return "";
        String sanitized = raw
            .replace("@everyone", "@\u200beveryone")
            .replace("@here", "@\u200bhere");
        if (sanitized.length() > DISCORD_MAX_EMBED_DESCRIPTION) {
            return sanitized.substring(0, DISCORD_MAX_EMBED_DESCRIPTION - 3) + "...";
        }
        return sanitized;
    }

    private static void loadLinkedAccounts() {
        if (LINKED_ACCOUNTS_FILE.exists()) {
            try (FileReader reader = new FileReader(LINKED_ACCOUNTS_FILE)) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> data = GSON.fromJson(reader, type);
                if (data != null) {
                    for (Map.Entry<String, String> entry : data.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            String discordId = entry.getValue();
                            discordToMinecraft.put(discordId, uuid);
                            minecraftToDiscord.put(uuid, discordId);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Invalid UUID in linked accounts: " + entry.getKey());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load linked accounts", e);
            }
        }
    }

    private static void saveLinkedAccounts() {
        try {
            LINKED_ACCOUNTS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(LINKED_ACCOUNTS_FILE)) {
                Map<String, String> data = new HashMap<>();
                for (Map.Entry<UUID, String> entry : minecraftToDiscord.entrySet()) {
                    data.put(entry.getKey().toString(), entry.getValue());
                }
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save linked accounts", e);
        }
    }

    // Discord Command Listener
    private static class DiscordCommandListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if (!ConfigManager.getConfig().discord.enableBidirectionalCommands) return;

            // Check if user has required role
            Member member = event.getMember();
            if (member == null) return;

            boolean hasPermission = ConfigManager.getConfig().discord.allowedDiscordRoles.length == 0;
            for (String roleId : ConfigManager.getConfig().discord.allowedDiscordRoles) {
                if (member.getRoles().stream().anyMatch(role -> role.getId().equals(roleId))) {
                    hasPermission = true;
                    break;
                }
            }

            if (!hasPermission) {
                event.reply("❌ You don't have permission to use this command.").setEphemeral(true).queue();
                return;
            }

            switch (event.getName()) {
                case "ban" -> handleBanCommand(event);
                case "eco" -> handleEcoCommand(event);
                case "bounty" -> handleBountyCommand(event);
            }
        }

        private void handleBanCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("player") == null) {
                event.reply("Missing required option: player").setEphemeral(true).queue();
                return;
            }
            String playerName = event.getOption("player").getAsString();
            String reason = event.getOption("reason") != null ? event.getOption("reason").getAsString() : "No reason provided";

            if (server != null) {
                server.execute(() ->
                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "ban " + playerName + " " + reason));
            }

            event.reply("🔨 Banned player: " + playerName + "\nReason: " + reason).queue();

            sendAdminActionLog("Discord ban executed by " + event.getUser().getEffectiveName() +
                "\nPlayer: " + playerName + "\nReason: " + reason);
        }

        private void handleEcoCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("action") == null || event.getOption("player") == null || event.getOption("amount") == null) {
                event.reply("Missing required options for /eco").setEphemeral(true).queue();
                return;
            }
            String action = event.getOption("action").getAsString();
            String playerName = event.getOption("player").getAsString();
            double amount = event.getOption("amount").getAsDouble();

            if (server != null) {
                server.execute(() ->
                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "eco " + action + " " + playerName + " " + amount));
            }

            event.reply("💰 " + action + " " + amount + " to/from " + playerName).queue();

            sendEconomyLog("Discord economy command by " + event.getUser().getEffectiveName() +
                "\nAction: " + action + "\nPlayer: " + playerName + "\nAmount: " + amount);
        }

        private void handleBountyCommand(SlashCommandInteractionEvent event) {
            if (event.getOption("action") == null) {
                event.reply("Missing required option: action").setEphemeral(true).queue();
                return;
            }
            String action = event.getOption("action").getAsString();

            if ("place".equals(action)) {
                if (event.getOption("player") == null || event.getOption("amount") == null) {
                    event.reply("Missing required options for bounty placement").setEphemeral(true).queue();
                    return;
                }
                String playerName = event.getOption("player").getAsString();
                double amount = event.getOption("amount").getAsDouble();

                if (server != null) {
                    server.execute(() ->
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "bounty set " + playerName + " " + amount));
                }

                event.reply("🎯 Placed bounty of " + amount + " on " + playerName).queue();

                sendBountyFeed("Discord bounty placed by " + event.getUser().getEffectiveName() +
                    "\nTarget: " + playerName + "\nAmount: " + amount);
            } else if ("list".equals(action)) {
                String list = getBountyList();
                event.reply(list).queue();
            } else {
                event.reply("Unknown action. Use: place or list").setEphemeral(true).queue();
            }
        }
    }

    private static String getBountyList() {
        Map<UUID, Double> bounties = BountyManager.getAllBounties();
        if (bounties.isEmpty()) {
            return "📋 No active bounties.";
        }

        StringBuilder sb = new StringBuilder("📋 Active bounties:\n");
        bounties.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(entry -> {
                String name = getPlayerName(entry.getKey());
                sb.append("- ").append(name).append(": ").append(EconomyManager.formatCurrency(entry.getValue())).append("\n");
            });
        return sb.toString().trim();
    }

    private static String getPlayerName(UUID uuid) {
        if (server == null) return uuid.toString().substring(0, 8);
        var online = server.getPlayerManager().getPlayer(uuid);
        if (online != null) {
            return online.getName().getString();
        }
        return uuid.toString().substring(0, 8);
    }

    private static class WebhookPayload {
        @SuppressWarnings("unused")
        public Embed[] embeds;

        public WebhookPayload(Embed embed) {
            this.embeds = new Embed[]{embed};
        }
    }

    private static class Embed {
        @SuppressWarnings("unused")
        public String title;
        @SuppressWarnings("unused")
        public String description;
        @SuppressWarnings("unused")
        public int color;
        @SuppressWarnings("unused")
        public String timestamp;
        @SuppressWarnings("unused")
        public Footer footer;
        @SuppressWarnings("unused")
        public Field[] fields;

        public Embed(String title, String description, int color, List<Field> fields) {
            this.title = title;
            this.description = description;
            this.color = color;
            this.timestamp = Instant.now().toString();
            this.footer = new Footer(server != null ? ("Core • " + server.getVersion()) : "Core");
            this.fields = fields == null ? new Field[0] : fields.toArray(new Field[0]);
        }
    }

    private static class Footer {
        @SuppressWarnings("unused")
        public String text;

        public Footer(String text) {
            this.text = text;
        }
    }

    private static class Field {
        @SuppressWarnings("unused")
        public String name;
        @SuppressWarnings("unused")
        public String value;
        @SuppressWarnings("unused")
        public boolean inline;

        public Field(String name, String value, boolean inline) {
            this.name = name;
            this.value = value;
            this.inline = inline;
        }
    }

    private static void shutdown() {
        botStarted = false;
        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Exception e) {
                LOGGER.warn("Error while shutting down JDA", e);
            } finally {
                jda = null;
            }
        }

        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EXECUTOR.shutdownNow();
        }
    }
}
