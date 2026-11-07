package day.alacraft.alalogger.mc.client;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.AlaLoggerBootstrap;
import day.alacraft.alalogger.BuildInfo;
import day.alacraft.alalogger.ChatText;
import day.alacraft.alalogger.ClientCrashCheck;
import day.alacraft.alalogger.UploadService;
import day.alacraft.alalogger.api.ApiError;
import day.alacraft.alalogger.api.ApiException;
import day.alacraft.alalogger.api.Insight;
import day.alacraft.alalogger.i18n.Messages;
import day.alacraft.alalogger.logs.LogFile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static day.alacraft.alalogger.mc.client.CrashOfferClient.language;
import static day.alacraft.alalogger.mc.client.CrashOfferClient.text;

/**
 * "The last game crashed": the files it left, an upload button for each, and
 * what to do with the link once there is one.
 *
 * <p>Nothing is sent until a button is clicked, and what is sent goes through the
 * same reading and cleaning as {@code /alalogger} - {@link UploadService#upload}
 * is the one way out. The text copied "for Discord" is built from the upload's
 * link and the site's first finding, never from the log itself, so nothing that
 * was not already cleaned can reach the clipboard.
 */
final class CrashOfferScreen extends Screen {

    private static final int MAX_ROWS = 5;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 320;
    private static final int GAP = 2;

    private final Screen parent;
    private final AlaLoggerBootstrap mod;
    private final ClientCrashCheck.Offer offer;
    private final List<Row> rows = new ArrayList<>();

    /** One file and what happened to it on this screen. */
    private static final class Row {
        final LogFile file;
        volatile boolean uploading;
        volatile UploadService.Upload upload;
        volatile String error;

        Row(LogFile file) {
            this.file = file;
        }
    }

    CrashOfferScreen(Screen parent, AlaLoggerBootstrap mod, ClientCrashCheck.Offer offer) {
        super(text("client_crash.title"));
        this.parent = parent;
        this.mod = mod;
        this.offer = offer;
        offer.files().stream().limit(MAX_ROWS).map(Row::new).forEach(rows::add);
    }

    @Override
    protected void init() {
        int center = width / 2;
        int y = 14;

        // StringWidget draws from its left edge, so a title as wide as the screen
        // sat in the corner. Sized to the text and placed in the middle instead.
        int titleWidth = font.width(getTitle());
        addRenderableWidget(new StringWidget((width - titleWidth) / 2, y, titleWidth, 12, getTitle(), font));
        y += 18;

        String intro = offer.files().size() == 1 && offer.abnormalExit()
                && offer.files().get(0).type() == day.alacraft.alalogger.logs.LogFileType.LOG
                ? "client_crash.no_report"
                : "client_crash.intro";
        MultiLineTextWidget introText = new MultiLineTextWidget(center - 160, y,
                Component.literal(Messages.get(language(), intro) + " "
                        + Messages.get(language(), "client_crash.privacy")), font)
                .setMaxWidth(320)
                .setCentered(true);
        addRenderableWidget(introText);
        y += 44;

        Insight finding = null;
        String lastError = null;

        // Each file is a block: its name on a line of its own, then the buttons on
        // the line below, centred. Names are long ("crash-2026-11-07_13.00.00-
        // client.txt") and button labels in some languages are too, so sharing one
        // line cut one or the other. Button widths follow their text in the
        // player's language.
        Component copyText = text("client_crash.copy");
        Component openText = text("client_crash.open");
        Component discordText = text("client_crash.discord");
        int copyWidth = buttonWidth(copyText);
        int openWidth = buttonWidth(openText);
        int discordWidth = buttonWidth(discordText);
        int actionsWidth = copyWidth + openWidth + discordWidth + 2 * GAP;
        int uploadWidth = Math.max(160, Math.max(buttonWidth(text("client_crash.upload")),
                buttonWidth(text("client_crash.uploading"))));
        int left = center - ROW_WIDTH / 2;

        for (Row row : rows) {
            addRenderableWidget(new StringWidget(left, y, ROW_WIDTH, 12,
                    Component.literal(row.file.name() + "  (" + size(row.file.size()) + ")"), font)
                    .setMaxWidth(ROW_WIDTH));
            y += 14;

            UploadService.Upload upload = row.upload;
            if (upload != null) {
                // The link as text: a tooltip wrapped it over two lines.
                String url = upload.result().url();
                addRenderableWidget(new StringWidget(left, y, ROW_WIDTH, 12,
                        Component.literal(url).withStyle(ChatFormatting.AQUA), font)
                        .setMaxWidth(ROW_WIDTH));
                y += 14;

                int x = center - actionsWidth / 2;
                addRenderableWidget(Button.builder(copyText, b -> copied(b, url))
                        .bounds(x, y, copyWidth, 20).build());
                x += copyWidth + GAP;
                addRenderableWidget(Button.builder(openText, b -> open(url))
                        .bounds(x, y, openWidth, 20).build());
                x += openWidth + GAP;
                addRenderableWidget(Button.builder(discordText, b -> copied(b, discordText(upload)))
                        .bounds(x, y, discordWidth, 20)
                        .tooltip(Tooltip.create(text("client_crash.discord.hint"))).build());
                if (finding == null && !upload.result().insights().isEmpty()) {
                    finding = upload.result().insights().get(0);
                }
            } else {
                Button send = Button.builder(
                                text(row.uploading ? "client_crash.uploading" : "client_crash.upload"),
                                b -> upload(row))
                        .bounds(center - uploadWidth / 2, y, uploadWidth, 20).build();
                send.active = !row.uploading;
                addRenderableWidget(send);
                if (row.error != null) {
                    lastError = row.error;
                }
            }

            y += ROW_HEIGHT + 6;
        }

        String note = lastError != null ? lastError
                : finding != null ? ChatText.plain(finding.message()) : null;
        if (note != null) {
            addRenderableWidget(new MultiLineTextWidget(center - 160, y + 4, Component.literal(note), font)
                    .setMaxWidth(320)
                    .setCentered(true));
        }

        addRenderableWidget(Button.builder(text("client_crash.close"), b -> onClose())
                .bounds(center - 60, height - 28, 120, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private void upload(Row row) {
        row.uploading = true;
        row.error = null;
        rebuildWidgets();

        mod.service().upload(row.file, language()).whenComplete((upload, failure) -> minecraft.execute(() -> {
            row.uploading = false;
            if (failure != null) {
                row.error = describe(failure);
                AlaLogger.LOGGER.warn("Upload of {} from the crash screen failed", row.file.name(), failure);
            } else {
                row.upload = upload;
            }
            // Only if this screen is still the one on display: the player may
            // have closed it while the upload was running.
            if (minecraft.gui.screen() == this) {
                rebuildWidgets();
            }
        }));
    }

    private void copy(String value) {
        minecraft.keyboardHandler.setClipboard(value);
    }

    /** Copies, and says so on the button: a silent copy looks like a click that did nothing. */
    private void copied(Button button, String value) {
        copy(value);
        button.setMessage(text("client_crash.copied"));
    }

    private void open(String url) {
        try {
            ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
        } catch (IllegalArgumentException e) {
            copy(url);
        }
    }

    /**
     * What a player pastes into a support channel: the link, what the game ran
     * on, and the site's first finding. Built from the upload result only.
     */
    private String discordText(UploadService.Upload upload) {
        AlaLoggerBootstrap.Platform platform = mod.platform();
        StringBuilder text = new StringBuilder(Messages.get(language(), "client_crash.discord.text",
                "url", upload.result().url(),
                "mc", platform.minecraftVersion(),
                "loader", loaderTitle(platform.loaderName()),
                "version", BuildInfo.version()));

        if (!upload.result().insights().isEmpty()) {
            text.append('\n').append(ChatText.plain(upload.result().insights().get(0).message()));
        }

        return text.toString();
    }

    /** The same sentences the command uses, for the cases a player can act on. */
    private String describe(Throwable failure) {
        String language = language();
        ApiException api = ApiException.of(failure);

        if (api.getCause() instanceof UploadService.EmptyLogException empty) {
            return Messages.get(language, "error.file_empty", "file", empty.getMessage());
        }

        ApiError error = api.error();
        if (error == null) {
            return Messages.get(language, "error.generic", "reason", ChatText.embedded(api.getMessage()));
        }

        return switch (error.code()) {
            case OFFLINE, TIMEOUT, TLS -> Messages.get(language, "error.offline", "host", mod.service().host());
            case RATE_LIMITED -> Messages.get(language, "error.rate_limited",
                    "minutes", Math.max(1, error.retryAfterSeconds(60) / 60));
            case TOO_LARGE -> Messages.get(language, "error.too_large", "file", "");
            case INVALID_TOKEN, INSUFFICIENT_SCOPE -> Messages.get(language, "error.invalid_token");
            default -> Messages.get(language, "error.generic", "reason", ChatText.embedded(error.message()));
        };
    }

    /**
     * The loader as people write it. The platform name is the machine form used
     * in the User-Agent ("neoforge"); a message for a human says "NeoForge".
     */
    private static String loaderTitle(String loader) {
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "neoforge" -> "NeoForge";
            case "forge" -> "Forge";
            default -> loader;
        };
    }

    private int buttonWidth(Component label) {
        return Math.max(40, font.width(label) + 12);
    }

    private static String size(long bytes) {
        return bytes < 1024 ? bytes + " B"
                : bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%d KB", bytes / 1024)
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
