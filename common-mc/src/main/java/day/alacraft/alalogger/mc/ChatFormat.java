package day.alacraft.alalogger.mc;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.i18n.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;

/**
 * Turns the mod's messages into chat components.
 *
 * <p>Kept apart from the command tree because it is the only place that knows
 * what the mod looks like. One uncoloured line for success and one red line for
 * every possible failure would leave a player unable to tell "no network" from
 * "log too large" without opening the server log — the very file they were
 * trying to share. Here each state has its own shape: a brand-coloured prefix,
 * grey detail, red only for something that actually failed.
 */
public final class ChatFormat {

    /**
     * The mod's own colour, on the prefix only.
     *
     * <p>Green rather than the aqua used for links: the two need to be different
     * colours or the eye cannot separate "who is talking" from "what to click",
     * which is exactly how the first version read — one solid block of cyan.
     */
    private static final ChatFormatting BRAND = ChatFormatting.GREEN;

    /** Body text. Grey, not white: softer against the world behind the chat. */
    private static final ChatFormatting BODY = ChatFormatting.GRAY;

    private ChatFormat() {
    }

    /**
     * Prefix plus body, each with its own colour.
     *
     * <p>Built on an empty, unstyled parent on purpose. A component's children
     * inherit its style, so appending to a coloured prefix — the obvious way to
     * write this — silently paints the whole line the prefix's colour.
     */
    public static MutableComponent prefixed(String language, MutableComponent body) {
        return Component.empty()
                .append(Component.literal(Messages.get(language, "prefix")).withStyle(BRAND))
                .append(body);
    }

    public static MutableComponent text(String language, String key, Object... args) {
        return Component.literal(Messages.get(language, key, args)).withStyle(BODY);
    }

    public static MutableComponent info(String language, String key, Object... args) {
        return prefixed(language, text(language, key, args));
    }

    public static MutableComponent error(String language, String key, Object... args) {
        return prefixed(language, Component.literal(Messages.get(language, key, args))
                .withStyle(ChatFormatting.RED));
    }

    public static MutableComponent detail(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * The uploaded log's address, as a clickable link.
     *
     * <p>Shown without the scheme: the URL is the payload of the whole feature
     * and a player is going to read it aloud, copy it into Discord or type it on
     * a phone. {@code alacraft.day/en/logs/aB3xY9kM} survives all three better
     * than the same string with eight extra characters in front.
     */
    public static MutableComponent link(String url) {
        String shown = url.replaceFirst("^https?://", "");

        return Component.literal(shown).withStyle(style -> style
                // Aqua and underlined: the one thing on the line that is meant to
                // be clicked should not share a colour with anything that is not.
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
    }

    /**
     * A bracketed action, e.g. {@code [delete]}.
     *
     * @param command the command it runs, without the leading slash
     */
    public static MutableComponent button(String language, String labelKey, String hoverKey, String command) {
        MutableComponent hover = hoverKey == null
                ? Component.literal("/" + command)
                : Component.literal(Messages.get(language, hoverKey));

        return Component.literal(Messages.get(language, labelKey)).withStyle(style -> style
                // Dimmer than the body text: the buttons are always there, so
                // they should sit behind the sentence rather than compete with it.
                .withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/" + command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    /** A line that fills the command into the input box instead of running it. */
    public static MutableComponent suggestion(String label, String command) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand("/" + command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("/" + command))));
    }

    public static MutableComponent space() {
        return Component.literal(" ");
    }

    /**
     * Severity colour for a finding. Warnings are yellow rather than red so the
     * one thing that actually stopped the server stands out from the noise
     * around it.
     */
    public static Style severity(String severity) {
        ChatFormatting colour = switch (severity == null ? "" : severity) {
            case "error" -> ChatFormatting.RED;
            case "warning" -> ChatFormatting.YELLOW;
            default -> ChatFormatting.WHITE;
        };

        return Style.EMPTY.withColor(colour);
    }

    /** Human-readable file size, because "2.4 MB" means more than "2516582". */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** The mod id doubles as the command name; kept here so both agree. */
    public static String command() {
        return AlaLogger.MOD_ID;
    }
}
