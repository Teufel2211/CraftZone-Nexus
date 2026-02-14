package core.clans;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import core.menu.MenuCommands;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ClanCommands {
    private static final SuggestionProvider<ServerCommandSource> CLAN_NAME_SUGGESTIONS = (context, builder) -> {
        List<String> names = new ArrayList<>(ClanManager.getAllClans().keySet());
        return CommandSource.suggestMatching(names, builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("clan")
            .executes(context -> MenuCommands.openClansMenu(context.getSource()))
            .then(CommandManager.literal("create")
                .then(CommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                    .then(CommandManager.argument("tag", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(context -> {
                            var player = context.getSource().getPlayer();
                            String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                            String tag = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "tag");
                            if (player != null && ClanManager.createClan(name, tag, player.getUuid())) {
                                context.getSource().sendMessage(Text.literal("Clan created"));
                            } else {
                                context.getSource().sendMessage(Text.literal("Clan creation failed"));
                            }
                            return 1;
                        })))
            .then(CommandManager.literal("join")
                .then(CommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                    .suggests(CLAN_NAME_SUGGESTIONS)
                    .executes(context -> {
                        var player = context.getSource().getPlayer();
                        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                        if (player != null && ClanManager.joinClan(player.getUuid(), name)) {
                            context.getSource().sendMessage(Text.literal("Joined clan"));
                        } else {
                            context.getSource().sendMessage(Text.literal("Join failed"));
                        }
                        return 1;
                    }))))
            .then(CommandManager.literal("leave")
                .executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player != null && ClanManager.leaveClan(player.getUuid())) {
                        context.getSource().sendMessage(Text.literal("Left clan"));
                    } else {
                        context.getSource().sendMessage(Text.literal("Leave failed"));
                    }
                    return 1;
                })));
    }
}
