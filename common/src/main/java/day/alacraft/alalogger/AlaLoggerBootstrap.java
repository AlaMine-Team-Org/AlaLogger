package day.alacraft.alalogger;

import day.alacraft.alalogger.api.AlaLoggerApi;
import day.alacraft.alalogger.history.UploadHistory;
import day.alacraft.alalogger.i18n.Messages;
import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything both loaders do, in the order they do it.
 *
 * <p>The Fabric and NeoForge entrypoints were once two files of a hundred and
 * ninety lines each, and the same hundred and ninety: load the config, build the
 * API client, assemble the service, warm the limits cache, announce the crash
 * files, print the startup lines. Only four calls actually differed — where the
 * loader keeps its directories, how a command is registered, and the names of
 * its two lifecycle events.
 *
 * <p>That arrangement asks every future fix to be made twice and offers nothing
 * that notices when it was made once. It already came close: a fix to the
 * NeoForge side had to be back-ported by hand, and the day the two files drift
 * the mod has quietly become two mods that share a name.
 *
 * <p>So the sequence lives here, in {@code common}, where no loader can reach
 * around it — and where it can be built in a unit test without a game. What is
 * left in a loader module is an adapter: hand this class a {@link Platform}, then
 * wire {@link #serverStarted()} and {@link #serverStopped(boolean)} to whatever
 * that loader calls its lifecycle events. {@code tools/loader_parity_check} in
 * the build enforces exactly that shape.
 */
public final class AlaLoggerBootstrap {

    /**
     * What a loader knows and this class cannot work out for itself.
     *
     * @param loaderName       {@code fabric} or {@code neoforge}, as it appears in the User-Agent
     *                         and in the {@code source} field of an upload
     * @param minecraftVersion the running game version, for the same two places
     * @param configDirectory  the loader's config directory. Everything the mod writes hangs off
     *                         this, including the delete tokens — a launcher that moves the config
     *                         directory has to take them with it, or they are stranded in a folder
     *                         the player never chose
     * @param gameDirectory    the root that holds {@code logs/}, {@code crash-reports/} and
     *                         {@code debug/}
     */
    public record Platform(
            String loaderName,
            String minecraftVersion,
            Path configDirectory,
            Path gameDirectory) {

        public Platform {
            loaderName = loaderName == null || loaderName.isBlank() ? "unknown" : loaderName;
            minecraftVersion = minecraftVersion == null || minecraftVersion.isBlank()
                    ? "unknown"
                    : minecraftVersion;
            Objects.requireNonNull(configDirectory, "configDirectory");
            Objects.requireNonNull(gameDirectory, "gameDirectory");
        }
    }

    private final Config config;
    private final AlaLoggerApi api;
    private final UploadService service;
    private final CrashWatch crashWatch;

    private AlaLoggerBootstrap(Config config, AlaLoggerApi api, UploadService service, CrashWatch crashWatch) {
        this.config = config;
        this.api = api;
        this.service = service;
        this.crashWatch = crashWatch;
    }

    /**
     * Reads the config, builds everything on top of it and says so in the console.
     *
     * <p>Never throws for a reason a player could have caused. The one input that
     * can be wrong in an interesting way is {@code apiBaseUrl}, and by the time it
     * gets here {@link Config} has already replaced an unusable one with the
     * default — losing uploads to a typo is a fair trade, losing the server is
     * not.
     */
    public static AlaLoggerBootstrap start(Platform platform) {
        Path configFile = platform.configDirectory().resolve(AlaLogger.MOD_ID + ".json");
        Path dataDirectory = platform.configDirectory().resolve(AlaLogger.MOD_ID);

        Config config = Config.load(configFile);
        AlaLoggerApi api = buildApi(config, platform);

        // One instance, shared with the crash watch. Building a second one for
        // the watch cost a duplicate scan of the JVM's arguments and the system
        // properties on every server start, and bought nothing: the object is
        // immutable and re-reads the disk on every call anyway.
        LogFiles files = LogFiles.forGameDirectory(platform.gameDirectory());

        UploadService service = new UploadService(config, api, files,
                new UploadHistory(dataDirectory.resolve("history.json")));

        AlaLoggerBootstrap mod = new AlaLoggerBootstrap(config, api, service,
                new CrashWatch(dataDirectory.resolve("crash-marker.json"), files));

        mod.announceReady(configFile);

        return mod;
    }

    public UploadService service() {
        return service;
    }

    public Config config() {
        return config;
    }

    /**
     * The server is up.
     *
     * <p>The limits are fetched now rather than during initialisation: a network
     * call while mods are still loading delays startup for something nobody has
     * asked for yet.
     */
    public void serverStarted() {
        service.warmUp();

        if (config.crashWatch) {
            announceCrashes();
        }
    }

    /**
     * The server is going away — let go of the HTTP client while there is still a
     * shutdown to do it in, so an upload in flight gets its grace period instead
     * of dying with the process.
     *
     * <p>Dedicated servers only. On a client this fires every time a singleplayer
     * world closes, and the client is built once per game launch: closing it there
     * would leave the next world with a dead uploader, which is a far worse trade
     * than one idle HTTP client per session.
     */
    public void serverStopped(boolean dedicatedServer) {
        if (dedicatedServer) {
            api.close();
        }
    }

    /**
     * Builds the API client, surviving a value {@link Config} could not repair.
     *
     * <p>Belt and braces: the config normalises {@code apiBaseUrl} before this
     * runs, so the fallback should be unreachable. It stays because the failure it
     * guards against is the loader refusing to start the mod at all, and because
     * the guard is five lines in one file rather than in every loader module.
     */
    private static AlaLoggerApi buildApi(Config config, Platform platform) {
        String userAgent = AlaLoggerApi.userAgent(
                BuildInfo.version(), platform.minecraftVersion(), platform.loaderName());

        try {
            return client(config.apiBaseUrl, config, userAgent);
        } catch (RuntimeException e) {
            AlaLogger.LOGGER.error(
                    "apiBaseUrl in the config is not a usable URL ({}): \"{}\". Falling back to {} — "
                            + "fix the config and restart to use your own instance.",
                    e.getMessage(), config.apiBaseUrl, AlaLogger.DEFAULT_API_BASE_URL);

            config.apiBaseUrl = AlaLogger.DEFAULT_API_BASE_URL;

            return client(AlaLogger.DEFAULT_API_BASE_URL, config, userAgent);
        }
    }

    private static AlaLoggerApi client(String baseUrl, Config config, String userAgent) {
        return AlaLoggerApi.builder(baseUrl)
                .userAgent(userAgent)
                .source(BuildInfo.sourceTag())
                .apiToken(config.apiToken)
                .build();
    }

    /**
     * Tell the operator the mod is here and what to type.
     *
     * <p>Saying nothing on startup is how "how do I use this" becomes a recurring
     * question in someone's Discord.
     */
    private void announceReady(Path configFile) {
        String language = config.consoleLanguage();

        AlaLogger.LOGGER.info("{} {} - {}", AlaLogger.MOD_NAME, BuildInfo.version(),
                Messages.get(language, "startup.ready",
                        "command", AlaLogger.MOD_ID,
                        "file", "logs/latest.log",
                        "host", config.apiBaseUrl));
        AlaLogger.LOGGER.info("{}", Messages.get(language, "startup.config", "path", configFile));
    }

    /**
     * Name the crash files that appeared since the last start.
     *
     * <p>Console only, and only a mention: this runs before anyone has joined, and
     * the point is that the file is found while it still exists, not that anything
     * is sent. Uploading stays a deliberate act.
     */
    private void announceCrashes() {
        List<LogFile> found = crashWatch.unreported();

        if (found.isEmpty()) {
            return;
        }

        String language = config.consoleLanguage();

        if (found.size() > CrashWatch.maxListed()) {
            AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.found_many",
                    "count", found.size(),
                    "command", "/" + AlaLogger.MOD_ID + " list"));

            return;
        }

        for (LogFile file : found) {
            AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.found", "file", file.name()));
        }

        AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.hint",
                "command", "/" + AlaLogger.MOD_ID + " crash"));
    }
}
