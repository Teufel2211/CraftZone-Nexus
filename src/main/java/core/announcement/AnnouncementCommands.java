package core.announcement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import core.discord.DiscordManager;
import core.util.Safe;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class AnnouncementCommands {
    private AnnouncementCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("announce")
            .requires(AnnouncementCommands::isAdmin);

        root.then(CommandManager.literal("chat")
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> announceChat(
                    ctx.getSource(),
                    StringArgumentType.getString(ctx, "message")))));

        root.then(CommandManager.literal("actionbar")
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> announceActionbar(
                    ctx.getSource(),
                    StringArgumentType.getString(ctx, "message")))));

        root.then(CommandManager.literal("both")
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> announceBoth(
                    ctx.getSource(),
                    StringArgumentType.getString(ctx, "message")))));

        root.then(CommandManager.literal("player")
            .then(CommandManager.argument("target", EntityArgumentType.player())
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> announcePlayer(
                        ctx.getSource(),
                        EntityArgumentType.getPlayer(ctx, "target"),
                        StringArgumentType.getString(ctx, "message"))))));

        root.then(CommandManager.literal("reload")
            .executes(ctx -> reloadAnnouncements(ctx.getSource())));

        dispatcher.register(root);
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    private static int announceChat(ServerCommandSource source, String message) {
        String trimmed = sanitize(message);
        if (trimmed.isEmpty()) return 0;

        Text payload = Text.literal("§6[Announcement] §f" + trimmed);
        source.getServer().getPlayerManager().broadcast(payload, false);
        Safe.run("DiscordManager.sendAdminActionLog", () -> DiscordManager.sendAdminActionLog("Announcement (chat): " + trimmed));
        source.sendMessage(Text.literal("§aAnnouncement sent to chat."));
        return 1;
    }

    private static int announceActionbar(ServerCommandSource source, String message) {
        String trimmed = sanitize(message);
        if (trimmed.isEmpty()) return 0;

        Text payload = Text.literal("§6[Announcement] §f" + trimmed);
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            player.sendMessage(payload, true);
        }
        Safe.run("DiscordManager.sendAdminActionLog", () -> DiscordManager.sendAdminActionLog("Announcement (actionbar): " + trimmed));
        source.sendMessage(Text.literal("§aAnnouncement sent to actionbar."));
        return 1;
    }

    private static int announceBoth(ServerCommandSource source, String message) {
        String trimmed = sanitize(message);
        if (trimmed.isEmpty()) return 0;

        Text payload = Text.literal("§6[Announcement] §f" + trimmed);
        source.getServer().getPlayerManager().broadcast(payload, false);
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            player.sendMessage(payload, true);
        }
        Safe.run("DiscordManager.sendAdminActionLog", () -> DiscordManager.sendAdminActionLog("Announcement (chat+actionbar): " + trimmed));
        source.sendMessage(Text.literal("§aAnnouncement sent to chat and actionbar."));
        return 1;
    }

    private static int announcePlayer(ServerCommandSource source, ServerPlayerEntity target, String message) {
        String trimmed = sanitize(message);
        if (trimmed.isEmpty()) return 0;

        Text payload = Text.literal("§6[Announcement] §f" + trimmed);
        target.sendMessage(payload, false);
        target.sendMessage(payload, true);
        Safe.run("DiscordManager.sendAdminActionLog", () -> DiscordManager.sendAdminActionLog("Announcement (player): " + target.getName().getString() + " -> " + trimmed));
        source.sendMessage(Text.literal("§aAnnouncement sent to " + target.getName().getString() + "."));
        return 1;
    }

    private static String sanitize(String message) {
        if (message == null) return "";
        return message.replace('\n', ' ').trim();
    }

    private static int reloadAnnouncements(ServerCommandSource source) {
        AnnouncementManager.reload();
        var cfg = AnnouncementManager.getConfig();
        if (cfg == null) {
            source.sendMessage(Text.literal("§cAnnouncement config reload failed."));
            return 0;
        }
        source.sendMessage(Text.literal("§aAnnouncements reloaded. enabled=" + cfg.enabled + ", interval=" + cfg.intervalSeconds + "s, messages=" + cfg.messages.size()));
        return 1;
    }
}
