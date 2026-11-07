package day.alacraft.alalogger.mc.client;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.AlaLoggerBootstrap;
import day.alacraft.alalogger.ClientCrashCheck;
import day.alacraft.alalogger.i18n.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The client half of "your last game crashed - share the log?".
 *
 * <p>Two calls from each loader's client entrypoint, nothing else:
 * {@link #init()} once, when the client mod is constructed, and
 * {@link #decorate(Screen, Consumer)} whenever a screen has built its widgets.
 * Everything that decides what to offer lives in {@code common}
 * ({@link ClientCrashCheck}); this class only shows it.
 *
 * <p>Client-only: it touches screen classes a dedicated server does not have, so
 * only client entrypoints may load it.
 */
public final class CrashOfferClient {

    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    private static volatile AlaLoggerBootstrap mod;
    private static volatile CompletableFuture<ClientCrashCheck.Offer> offer;
    private static volatile boolean announced;
    private static volatile boolean opened;
    private static volatile boolean deferred;

    private CrashOfferClient() {
    }

    /**
     * Starts this session's record and the look at the previous one. A JVM
     * shutdown hook marks a clean exit: it runs on a normal quit, and does not
     * when the process is killed or dies in native code - exactly the two cases
     * that leave no crash report behind.
     */
    public static void init() {
        AlaLoggerBootstrap running = AlaLoggerBootstrap.running().orElse(null);
        if (running == null) {
            // A loader may construct the client entrypoint before the main one.
            // The first screen comes long after both, so try again from there.
            deferred = true;
            return;
        }
        deferred = false;

        mod = running;
        offer = running.clientStarted();
        offer.thenAccept(found -> {
            if (!found.isEmpty()) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST,
                            Component.literal(AlaLogger.MOD_NAME), text("client_crash.toast"));

                    // The look at the disk can finish after the title screen was
                    // built - on Forge, whose mods load late, it did, and the
                    // notice never appeared. Building the title screen again goes
                    // through the same loader hooks as a window resize, so
                    // decorate() gets its turn.
                    Screen current = minecraft.gui.screen();
                    if (current instanceof TitleScreen && !announced) {
                        current.init(current.width, current.height);
                    }
                });
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(running::clientStopping, "alalogger-session"));
    }

    /** Adds the notice to the title screen while there is something to offer. */
    public static void decorate(Screen screen, Consumer<AbstractWidget> add) {
        if (deferred) {
            init();
        }
        if (screen instanceof TitleScreen) {
            AlaLogger.LOGGER.debug("Crash offer: title screen built; offer ready={}, opened={}",
                    offer != null && offer.isDone(), opened);
        }
        if (!(screen instanceof TitleScreen) || opened || offer == null) {
            return;
        }

        ClientCrashCheck.Offer found = offer.getNow(null);
        if (found == null || found.isEmpty()) {
            return;
        }

        if (!announced) {
            // Shown once means offered once: a player who saw the notice and
            // chose to play on is not asked again next launch.
            announced = true;
            mod.crashOfferShown(found);
        }

        // A small "!" beside Multiplayer rather than a wide button in a corner:
        // the top edge is where NeoForge prints its beta warning and where other
        // mods draw too, and a wide button there sat on top of their text. The
        // right of the second main-menu row is empty in vanilla, on Fabric and on
        // NeoForge. The full sentence is the tooltip; the toast says it as well.
        Button notice = Button.builder(Component.literal("!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                        button -> {
                            opened = true;
                            Minecraft.getInstance().gui.setScreen(new CrashOfferScreen(screen, mod, found));
                        })
                .bounds(screen.width / 2 + 104, screen.height / 4 + 48 + 24, 20, 20)
                .tooltip(Tooltip.create(Component.literal(Messages.get(language(), "client_crash.notice")
                        + "\n" + Messages.get(language(), "client_crash.notice.hint"))))
                .build();

        add.accept(notice);
    }

    /** The player's language: the pinned one from the config, or the game's own. */
    static String language() {
        AlaLoggerBootstrap current = mod;
        String selected = Minecraft.getInstance().getLanguageManager().getSelected();
        return current == null ? selected : current.config().pinnedLanguage().orElse(selected);
    }

    static Component text(String key, Object... args) {
        return Component.literal(Messages.get(language(), key, args));
    }
}
