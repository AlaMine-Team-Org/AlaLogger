package day.alacraft.alalogger.fabric;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.BuildInfo;
import day.alacraft.alalogger.Config;
import day.alacraft.alalogger.CrashWatch;
import day.alacraft.alalogger.UploadService;
import day.alacraft.alalogger.api.AlaLoggerApi;
import day.alacraft.alalogger.history.UploadHistory;
import day.alacraft.alalogger.i18n.Messages;
import day.alacraft.alalogger.logs.LogFiles;
import day.alacraft.alalogger.mc.AlaLoggerCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric entrypoint.
 *
 * <p>Thin on purpose: it locates the game's directories, builds the shared
 * service out of them and hands the command tree to Fabric's registry. Adding
 * NeoForge or Paper later means writing this file again for that platform and
 * nothing else — everything a player interacts with already lives in the shared
 * modules.
 */
public class AlaLoggerFabric implements ModInitializer {

    private static Config config;
    private static UploadService service;

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();

        Path configFile = loader.getConfigDir().resolve(AlaLogger.MOD_ID + ".json");
        Path gameDirectory = loader.getGameDir();

        // Everything the mod writes hangs off the loader's config directory, the
        // same one the config file itself came from. Building this from the game
        // directory instead used to be right by coincidence: a launcher or an
        // instance manager that moves the config directory (`fabric.configDir`)
        // would have left the delete tokens in a folder the player never chose —
        // possibly one that is synced or shared.
        Path dataDirectory = loader.getConfigDir().resolve(AlaLogger.MOD_ID);

        config = Config.load(configFile);

        AlaLoggerApi api = buildApi(config, minecraftVersion(loader));

        service = new UploadService(
                config,
                api,
                LogFiles.forGameDirectory(gameDirectory),
                new UploadHistory(dataDirectory.resolve("history.json"))
        );

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AlaLoggerCommand.register(dispatcher, service));

        // Fetch the server's limits once the game is up rather than during
        // initialisation: a network call while mods are still loading delays
        // startup for something no one has asked for yet.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            service.warmUp();

            if (config.crashWatch) {
                announceCrashes(new CrashWatch(
                        dataDirectory.resolve("crash-marker.json"),
                        LogFiles.forGameDirectory(gameDirectory)));
            }
        });

        // Let go of the HTTP client and its threads while there is still a
        // shutdown to do it in, so an upload in flight gets its five seconds of
        // grace instead of dying with the process.
        //
        // Dedicated servers only. On a client this event also fires every time a
        // singleplayer world is closed, and the client is built once per game
        // launch — closing it there would leave the next world with a dead
        // uploader, which is a far worse trade than one idle client per session.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server.isDedicatedServer()) {
                api.close();
            }
        });

        // Tell the operator the mod is here and what to type. Saying nothing on
        // startup is how "how do I use this" becomes a recurring question.
        String language = "auto".equals(config.language) ? "en_us" : config.language;
        AlaLogger.LOGGER.info("{} {} - {}", AlaLogger.MOD_NAME, BuildInfo.version(),
                Messages.get(language, "startup.ready",
                        "command", AlaLogger.MOD_ID,
                        "file", "logs/latest.log",
                        "host", config.apiBaseUrl));
        AlaLogger.LOGGER.info("{}", Messages.get(language, "startup.config", "path", configFile));
    }

    /**
     * Build the API client, surviving a typo in the config.
     *
     * <p>{@code Config} deliberately never fails a server over a broken config
     * file, but the URL check lives in the client's builder — so a missing
     * {@code https://} threw out of {@code onInitialize()} and Fabric refused to
     * start the mod at all. Losing log uploads because of a typo is a fair
     * trade; losing the server is not.
     */
    private static AlaLoggerApi buildApi(Config config, String minecraftVersion) {
        String userAgent = AlaLoggerApi.userAgent(BuildInfo.version(), minecraftVersion, "fabric");

        try {
            return AlaLoggerApi.builder(config.apiBaseUrl)
                    .userAgent(userAgent)
                    .source(BuildInfo.sourceTag())
                    .apiToken(config.apiToken)
                    .build();
        } catch (RuntimeException e) {
            AlaLogger.LOGGER.error(
                    "apiBaseUrl in the config is not a usable URL ({}): \"{}\". Falling back to {} — "
                            + "fix the config and restart to use your own instance.",
                    e.getMessage(), config.apiBaseUrl, AlaLogger.DEFAULT_API_BASE_URL);

            config.apiBaseUrl = AlaLogger.DEFAULT_API_BASE_URL;

            return AlaLoggerApi.builder(AlaLogger.DEFAULT_API_BASE_URL)
                    .userAgent(userAgent)
                    .source(BuildInfo.sourceTag())
                    .apiToken(config.apiToken)
                    .build();
        }
    }

    /**
     * Tell the operator about crash files that appeared since the last start.
     *
     * <p>Console only, and only a mention: this runs before anyone has joined,
     * and the point is that the file is found while it still exists, not that
     * anything is sent. Uploading stays a deliberate act.
     */
    private static void announceCrashes(CrashWatch watch) {
        var found = watch.unreported();

        if (found.isEmpty()) {
            return;
        }

        String language = "auto".equals(config.language) ? "en_us" : config.language;

        if (found.size() > CrashWatch.maxListed()) {
            AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.found_many",
                    "count", found.size(),
                    "command", "/" + AlaLogger.MOD_ID + " list"));
            return;
        }

        for (var file : found) {
            AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.found", "file", file.name()));
        }

        AlaLogger.LOGGER.warn("{}", Messages.get(language, "crashwatch.hint",
                "command", "/" + AlaLogger.MOD_ID + " crash"));
    }

    /** The running Minecraft version, for the User-Agent we identify ourselves with. */
    private static String minecraftVersion(FabricLoader loader) {
        return loader.getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static Config config() {
        return config;
    }

    public static UploadService service() {
        return service;
    }
}
