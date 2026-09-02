package day.alacraft.alalogger.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.context.CommandContext;
import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.ChatText;
import day.alacraft.alalogger.Config;
import day.alacraft.alalogger.UploadService;
import day.alacraft.alalogger.api.ApiError;
import day.alacraft.alalogger.api.ApiException;
import day.alacraft.alalogger.api.Insight;
import day.alacraft.alalogger.history.UploadRecord;
import day.alacraft.alalogger.i18n.Messages;
import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFileType;
import day.alacraft.alalogger.redact.RedactionSummaries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The command tree, written once for every loader.
 *
 * <p>Nothing here is Fabric- or NeoForge-specific: each loader only has to hand
 * this class its dispatcher. That is the payoff of compiling the module against
 * NeoForm instead of a loader — the interesting half of the mod, the part that
 * decides what a player sees when their server just died, exists in one copy.
 */
public final class AlaLoggerCommand {

    /** How many files to list before telling the player to narrow it down. */
    private static final int LIST_LIMIT = 15;

    /**
     * What the player asked for, so a failure can name the thing that failed.
     *
     * <p>Every error used to read "Upload failed:" regardless of the command —
     * a failed delete told the player the opposite of what had happened, and a
     * failed listing blamed an upload that was never attempted. Seen in game on
     * 2026-09-02: "Upload failed: Log not found." in answer to a delete.
     */
    private enum Operation {

        UPLOAD("error.generic"),
        DELETE("error.delete_failed"),
        INSIGHTS("error.insights_failed"),
        LIST("error.list_failed");

        private final String genericKey;

        Operation(String genericKey) {
            this.genericKey = genericKey;
        }
    }

    private AlaLoggerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, UploadService service) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(build(ChatFormat.command(), service));

        // A short alias, because the long form is painful to type on a phone —
        // and a phone is what an admin has when the server dies while they are
        // out.
        //
        // A redirect rather than a second tree: one node means the subcommands,
        // permissions and suggestions cannot drift apart between the two names.
        dispatcher.register(Commands.literal("alog")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> uploadCurrent(ctx, service))
                .redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name, UploadService service) {
        return Commands.literal(name)
                // Operator level 2, the same bar vanilla sets for /ban and
                // /whitelist: uploading a log publishes what the server has been
                // doing, so it is not a thing an ordinary player should be able
                // to do on someone else's server.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> uploadCurrent(ctx, service))
                .then(Commands.literal("share")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    for (LogFile file : service.files().list()) {
                                        builder.suggest(file.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> uploadNamed(ctx, service, StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("crash")
                        .executes(ctx -> uploadCrash(ctx, service)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, service, null))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> list(ctx, service, StringArgumentType.getString(ctx, "filter")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("last");
                                    for (UploadRecord record : service.history().recent(10)) {
                                        builder.suggest(record.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> confirmDelete(ctx, service, StringArgumentType.getString(ctx, "id")))
                                // The actual deletion hides behind a second word,
                                // so the button in chat is a two-step action and a
                                // stray click cannot destroy a link somebody has
                                // already pasted into a support thread.
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> delete(ctx, service,
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("insights")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> insights(ctx, service, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("help")
                        .executes(ctx -> help(ctx, service)));
    }

    // ------------------------------------------------------------- uploading

    private static int uploadCurrent(CommandContext<CommandSourceStack> ctx, UploadService service) {
        return resolveThenUpload(ctx, service, service.current(), null, "search.start", "error.no_log_file");
    }

    private static int uploadNamed(CommandContext<CommandSourceStack> ctx, UploadService service, String name) {
        return resolveThenUpload(ctx, service, service.find(name), name, "search.start", "error.file_not_found");
    }

    /**
     * The crash shortcut: newest crash report or JVM crash file, no name needed.
     *
     * <p>Requiring the filename asks for the one thing nobody knows at that
     * moment. Finding hs_err files matters for the same reason: the crash that
     * took the JVM down with it is the one people most need to share.
     */
    private static int uploadCrash(CommandContext<CommandSourceStack> ctx, UploadService service) {
        return resolveThenUpload(ctx, service,
                service.latest(LogFileType.CRASH_REPORT, LogFileType.JVM_CRASH, LogFileType.NETWORK_REPORT),
                null, "search.start_crash", "error.no_crash_report");
    }

    /**
     * Say something immediately, find the file in the background, then upload it.
     *
     * <p>The "looking for the file" line exists because of how the first version
     * felt: the command did its disk work before saying anything, so on a large
     * log it looked frozen and people reasonably assumed it had failed. Every
     * path now answers in the same tick it was typed.
     *
     * <p>Both the waiting line and the nothing-found line are the caller's,
     * because only the caller knows what is being looked for: /alalogger crash
     * announced "looking for the log file" and then answered about crash
     * reports, which reads as two different commands talking.
     */
    private static int resolveThenUpload(CommandContext<CommandSourceStack> ctx, UploadService service,
            CompletableFuture<Optional<LogFile>> lookup, String requestedName, String searchKey,
            String emptyKey) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String language = language(ctx, service);

        reply(ctx, ChatFormat.info(language, searchKey));

        lookup.whenComplete((found, error) -> server.execute(() -> {
            if (error != null) {
                replyError(source, language, error, service, Operation.UPLOAD, null);
                return;
            }

            // Which nothing was found matters: "no crash reports" and "no log
            // file" send the player to different places, and neither is the
            // empty upload history that this branch used to report.
            if (found.isEmpty()) {
                source.sendFailure(requestedName == null
                        ? ChatFormat.error(language, emptyKey,
                                "command", "/" + ChatFormat.command() + " list")
                        : ChatFormat.error(language, "error.file_not_found",
                                "file", requestedName, "command", "/" + ChatFormat.command() + " list"));
                return;
            }

            startUpload(source, server, service, language, found.get());
        }));

        return 1;
    }

    private static void startUpload(CommandSourceStack source, MinecraftServer server, UploadService service,
            String language, LogFile file) {
        // Name the file and its size before any work starts, so a slow upload
        // reads as "this is happening" rather than as silence.
        source.sendSuccess(() -> ChatFormat.info(language, "upload.start",
                "file", file.name(), "size", ChatFormat.size(file.size())), false);

        service.upload(file, language)
                // Back onto the server thread before touching anything the game
                // owns. The HTTP call finished on a background thread, and chat
                // is not thread-safe.
                .whenComplete((upload, error) -> server.execute(() -> {
                    if (error != null) {
                        replyError(source, language, error, service, Operation.UPLOAD, null);
                        return;
                    }

                    announce(source, language, upload, service.config().broadcastToAdmins,
                            service.config().insightsInChat);
                }));
    }

    private static void announce(CommandSourceStack source, String language, UploadService.Upload upload,
            boolean broadcastToAdmins, int insightLimit) {
        MutableComponent line = ChatFormat.info(language, "upload.success")
                .append(ChatFormat.space())
                .append(ChatFormat.link(upload.result().url()))
                .append(ChatFormat.space())
                // Labelled for what it does: the button runs `insights`, and the
                // address next to it is already the way to open the log in a
                // browser. It used to say [open] over the insights command, which
                // is a promise the click does not keep.
                .append(ChatFormat.button(language, "button.insights", "button.insights.hint",
                        ChatFormat.command() + " insights " + upload.result().id()))
                .append(ChatFormat.space())
                .append(ChatFormat.button(language, "button.delete", "button.delete.hint",
                        ChatFormat.command() + " delete " + upload.result().id()));

        // Vanilla behaviour for administrative commands: other operators see
        // that a log left the server. Off by config for anyone who considers
        // that noise.
        source.sendSuccess(() -> line, broadcastToAdmins);

        String removed = RedactionSummaries.describe(language, upload.redaction().matchedSummary());

        if (!removed.isEmpty()) {
            source.sendSuccess(() -> ChatFormat.info(language, "upload.cleaned", "what", removed), false);
        }

        if (upload.truncated()) {
            String key = upload.mode() == day.alacraft.alalogger.logs.ReadMode.TAIL
                    ? "upload.trimmed_tail"
                    : "upload.trimmed_head";
            source.sendSuccess(() -> ChatFormat.info(language, key,
                    "limit", ChatFormat.size(upload.result().size()),
                    "lines", upload.result().lines()), false);
        }

        sendInsights(source, language, upload.result().insights(), insightLimit);
    }

    /**
     * The findings, in chat.
     *
     * <p>This is the reason the mod is worth installing rather than bookmarking
     * a website: the person who needs the answer is looking at a console, not a
     * browser.
     */
    private static void sendInsights(CommandSourceStack source, String language, List<Insight> insights, int limit) {
        if (limit <= 0) {
            return;
        }

        if (insights == null || insights.isEmpty()) {
            source.sendSuccess(() -> ChatFormat.info(language, "insights.none"), false);
            return;
        }

        source.sendSuccess(() -> ChatFormat.info(language, "insights.header",
                "count", insights.size()), false);

        int shown = 0;
        for (Insight insight : insights) {
            if (shown++ >= limit) {
                break;
            }

            MutableComponent item = Component.literal("  " + shown + ". ")
                    .append(Component.literal(insight.message()).withStyle(ChatFormat.severity(insight.severity())));

            source.sendSuccess(() -> item, false);

            if (insight.hint() != null && !insight.hint().isBlank()) {
                source.sendSuccess(() -> ChatFormat.detail("     " + insight.hint()), false);
            }
        }
    }

    // -------------------------------------------------------------- listing

    private static int list(CommandContext<CommandSourceStack> ctx, UploadService service, String filter) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String language = language(ctx, service);

        reply(ctx, ChatFormat.info(language, "list.searching"));

        service.list(filter).whenComplete((files, error) -> server.execute(() -> {
            if (error != null) {
                replyError(source, language, error, service, Operation.LIST, null);
                return;
            }
            renderList(source, language, files, filter);
        }));

        return 1;
    }

    private static void renderList(CommandSourceStack source, String language, List<LogFile> files, String filter) {
        if (files.isEmpty()) {
            source.sendSuccess(() -> ChatFormat.info(language,
                    filter == null ? "list.empty" : "list.filtered_empty",
                    "filter", String.valueOf(filter)), false);
            return;
        }

        LogFileType heading = null;
        int shown = 0;
        Instant now = Instant.now();

        for (LogFile file : files) {
            if (shown >= LIST_LIMIT) {
                int rest = files.size() - shown;
                source.sendSuccess(() -> ChatFormat.info(language, "list.more",
                        "count", rest, "command", "/" + ChatFormat.command() + " list <filter>"), false);
                break;
            }

            if (file.type() != heading) {
                heading = file.type();
                LogFileType shownHeading = heading;
                source.sendSuccess(() -> ChatFormat.info(language, headingKey(shownHeading)), false);
            }

            // Size and age: without them you cannot tell which of five rotated
            // logs is the one that has your crash in it.
            String label = "  " + file.name() + " ("
                    + ChatFormat.size(file.size()) + ", " + age(file.age(now)) + ")";

            source.sendSuccess(
                    () -> ChatFormat.suggestion(label, ChatFormat.command() + " share " + file.name()), false);
            shown++;
        }
    }

    private static String headingKey(LogFileType type) {
        return switch (type) {
            case CRASH_REPORT -> "list.header.crashes";
            case NETWORK_REPORT -> "list.header.network";
            case JVM_CRASH -> "list.header.jvm";
            default -> "list.header.logs";
        };
    }

    private static String age(Duration age) {
        long minutes = Math.max(0, age.toMinutes());

        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 60 * 24) {
            return (minutes / 60) + "h";
        }
        return (minutes / (60 * 24)) + "d";
    }

    // -------------------------------------------------------------- history

    private static int delete(CommandContext<CommandSourceStack> ctx, UploadService service, String id) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String language = language(ctx, service);

        reply(ctx, ChatFormat.info(language, "delete.working", "id", id));

        service.delete(id).whenComplete((deleted, error) -> server.execute(() -> {
            if (error != null) {
                if (ApiException.of(error).getCause() instanceof UploadService.UnknownUploadException) {
                    source.sendFailure(ChatFormat.error(language, "delete.not_found", "id", id));
                    return;
                }
                replyError(source, language, error, service, Operation.DELETE, id);
                return;
            }

            source.sendSuccess(() -> ChatFormat.info(language, "delete.success", "id", deleted), true);
        }));

        return 1;
    }

    /**
     * Show the findings for an earlier upload again.
     *
     * <p>Asks the site rather than replaying a stored copy: the diagnosis is the
     * site's, and its detectors improve, so a log uploaded last month can come
     * back read by today's rules. It also means this works for an id somebody
     * pasted into chat, not only for the ones this server uploaded.
     */
    private static int insights(CommandContext<CommandSourceStack> ctx, UploadService service, String id) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String language = language(ctx, service);

        Optional<UploadRecord> record = service.recall(id);
        String resolved = record.map(UploadRecord::id).orElse(id);

        record.ifPresent(found -> reply(ctx, ChatFormat.info(language, "upload.success")
                .append(ChatFormat.space())
                .append(ChatFormat.link(found.url()))));

        service.insights(resolved, language).whenComplete((insights, error) -> server.execute(() -> {
            if (error != null) {
                replyError(source, language, error, service, Operation.INSIGHTS, resolved);
                return;
            }

            sendInsights(source, language, insights, service.config().insightsInChat);
        }));

        return 1;
    }

    /**
     * Ask before deleting.
     *
     * <p>A [delete] button that fires straight away makes a misclick
     * unrecoverable: the token is one-use and the link is usually already pasted
     * somewhere. The confirmation costs one click and removes that entire class
     * of accident.
     */
    private static int confirmDelete(CommandContext<CommandSourceStack> ctx, UploadService service, String id) {
        String language = language(ctx, service);
        Optional<UploadRecord> record = service.recall(id);

        if (record.isEmpty()) {
            reply(ctx, ChatFormat.error(language, "delete.not_found", "id", id));
            return 0;
        }

        String resolved = record.get().id();

        reply(ctx, ChatFormat.info(language, "delete.confirm", "id", resolved)
                .append(ChatFormat.space())
                .append(ChatFormat.button(language, "button.confirm_delete", "button.delete.hint",
                        ChatFormat.command() + " delete " + resolved + " confirm")));

        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx, UploadService service) {
        String language = language(ctx, service);
        String command = ChatFormat.command();

        reply(ctx, ChatFormat.info(language, "help.header"));

        record Entry(String usage, String key) {
        }

        List<Entry> entries = List.of(
                new Entry("", "help.upload"),
                new Entry(" share <file>", "help.share"),
                new Entry(" crash", "help.crash"),
                new Entry(" list [filter]", "help.list"),
                new Entry(" insights <id>", "help.insights"),
                new Entry(" delete <id>", "help.delete")
        );

        for (Entry entry : entries) {
            reply(ctx, ChatFormat.suggestion("  /" + command + entry.usage(), command + entry.usage())
                    .append(ChatFormat.detail(" — " + Messages.get(language, entry.key()))));
        }

        reply(ctx, ChatFormat.detail("  " + Messages.get(language, "help.footer")));

        return 1;
    }

    // --------------------------------------------------------------- shared

    /**
     * Turn a failure into the one sentence that tells the player what to do.
     *
     * <p>Every branch here exists because the alternative is one "An error
     * occurred, check your log for details" for all of them — including the case
     * where the log is precisely what could not be uploaded.
     */
    private static void replyError(CommandSourceStack source, String language, Throwable error,
            UploadService service, Operation operation, String id) {
        ApiException failure = ApiException.of(error);
        Throwable cause = failure.getCause();

        if (cause instanceof UploadService.EmptyLogException empty) {
            source.sendFailure(ChatFormat.error(language, "error.file_empty", "file", empty.getMessage()));
            return;
        }

        ApiError apiError = failure.error();

        if (apiError == null) {
            source.sendFailure(ChatFormat.error(language, operation.genericKey,
                    "reason", ChatText.embedded(failure.getMessage()), "id", String.valueOf(id)));
            AlaLogger.LOGGER.warn("{} failed", operation, error);
            return;
        }

        MutableComponent message = switch (apiError.code()) {
            case OFFLINE, TIMEOUT, TLS -> ChatFormat.error(language, "error.offline", "host", service.host());
            case RATE_LIMITED -> rateLimited(language, apiError, service.config().hasApiToken());
            case TOO_LARGE -> ChatFormat.error(language, "error.too_large", "file", "");
            case INVALID_TOKEN, INSUFFICIENT_SCOPE -> ChatFormat.error(language, "error.invalid_token");
            // "Log not found" is the site saying the id is gone, not that
            // something went wrong here: retention runs from the last read, so
            // a link from a few months ago legitimately answers this way.
            case NOT_FOUND -> id == null
                    ? ChatFormat.error(language, operation.genericKey,
                            "reason", ChatText.embedded(apiError.message()), "id", String.valueOf(id))
                    : ChatFormat.error(language, "error.gone", "id", id, "host", service.host());
            case REMOVED -> ChatFormat.error(language, "error.removed", "id", String.valueOf(id));
            default -> ChatFormat.error(language, operation.genericKey,
                    "reason", ChatText.embedded(apiError.message()), "id", String.valueOf(id));
        };

        source.sendFailure(message);

        // The console keeps the technical detail; chat keeps the sentence.
        AlaLogger.LOGGER.warn("{} failed: {} ({})", operation, apiError.message(), apiError.code());
    }

    /**
     * The wait, and — when there is no token in the config — the way out of it.
     *
     * <p>A server on shared hosting shares its address with everyone else on the
     * box, so the anonymous limit can be reached by somebody the admin has never
     * met. Naming the token here is the only place a player learns that the
     * limit is not fixed; without it the message is a dead end.
     */
    private static MutableComponent rateLimited(String language, ApiError error, boolean hasApiToken) {
        if (!hasApiToken) {
            return ChatFormat.error(language, "error.rate_limited_token");
        }

        long minutes = Math.max(1, error.retryAfterSeconds(60) / 60);

        return ChatFormat.error(language, "error.rate_limited", "minutes", minutes);
    }

    /**
     * The language to answer in.
     *
     * <p>With the default {@code auto}, a player gets their own client language
     * and the console — which has nobody to ask — gets English. A pinned value
     * overrides both, which is what a server whose whole staff speaks one
     * language actually wants.
     *
     * <p>This is the reason the mod resolves text server-side instead of sending
     * translatable components: it works for players who do not have the mod
     * installed, which is all of them.
     */
    private static String language(CommandContext<CommandSourceStack> ctx, UploadService service) {
        Config config = service.config();
        ServerPlayer player = ctx.getSource().getPlayer();

        return player == null
                ? config.consoleLanguage()
                : config.pinnedLanguage().orElseGet(() -> player.clientInformation().language());
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, MutableComponent message) {
        ctx.getSource().sendSuccess(() -> message, false);
    }
}
