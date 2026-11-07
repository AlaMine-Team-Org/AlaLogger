package day.alacraft.alalogger.mc;

import day.alacraft.alalogger.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.function.Function;

/**
 * Tells the other operators that a log left the server, each in their own language.
 *
 * <p>Vanilla's {@code sendSuccess(message, true)} does this already, but with one
 * message for everybody: the text was rendered in the language of whoever ran
 * the command, so a German admin was told about an upload in Russian. The mod
 * resolves its text on the server (vanilla clients do not have our translations),
 * so the only way to give each admin their own language is to render once per
 * recipient.
 *
 * <p>Otherwise this follows vanilla's {@code CommandSourceStack.broadcastToAdmins}
 * (checked with javap on 26.2 and 26.3, identical): the same {@code chat.type.admin}
 * frame in grey italics, the same two game rules, operators only, never the
 * caller. One difference, on purpose: the console copy is written only when a
 * player ran the command — vanilla also writes it for RCON and command blocks,
 * which the mod cannot tell apart from the console without vanilla's private field.
 */
final class AdminBroadcast {

    private AdminBroadcast() {
    }

    static void send(CommandSourceStack source, Config config, Function<String, Component> message) {
        if (source.isSilent()) {
            return;
        }

        MinecraftServer server = source.getServer();
        ServerPlayer caller = source.getPlayer();
        GameRules rules = source.getLevel().getGameRules();

        if (rules.get(GameRules.SEND_COMMAND_FEEDBACK)) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == caller || !server.getPlayerList().isOp(player.nameAndId())) {
                    continue;
                }

                String language = config.pinnedLanguage().orElseGet(() -> player.clientInformation().language());
                player.sendSystemMessage(frame(source, message.apply(language)));
            }
        }

        if (caller != null && rules.get(GameRules.LOG_ADMIN_COMMANDS)) {
            server.sendSystemMessage(frame(source, message.apply(config.consoleLanguage())));
        }
    }

    private static Component frame(CommandSourceStack source, Component message) {
        return Component.translatable("chat.type.admin", source.getDisplayName(), message)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }
}
