package day.alacraft.alalogger.neoforge;

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
import net.minecraft.SharedConstants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Path;

/**
 * NeoForge entrypoint.
 *
 * <p>The counterpart of {@code day.alacraft.alalogger.fabric.AlaLoggerFabric},
 * and deliberately its mirror image: the same steps in the same order, with only
 * the four loader-specific calls differing. Everything a player interacts with —
 * the command tree, the redaction rules, the translations, the history file —
 * lives in {@code common} and {@code common-mc} and is compiled into both jars
 * from the same source.
 *
 * <p>That is what makes a fix a fix for both loaders. When these two files start
 * to drift, the mod has quietly become two mods.
 */
@Mod(AlaLogger.MOD_ID)
public class AlaLoggerNeoForge {

    private static Config config;
    private static UploadService service;
    private static AlaLoggerApi api;

    public AlaLoggerNeoForge(IEventBus modBus) {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(AlaLogger.MOD_ID + ".json");
        Path gameDirectory = FMLPaths.GAMEDIR.get();

        // Same reasoning as the Fabric side: everything the mod writes hangs off
        // the loader's config directory, the one the config file itself came
        // from, so an instance manager that moves it takes the delete tokens
        // along instead of stranding them in a folder nobody chose.
        Path dataDirectory = FMLPaths.CONFIGDIR.get().resolve(AlaLogger.MOD_ID);

        config = Config.load(configFile);

        api = buildApi(config, minecraftVersion());

        service = new UploadService(
                config,
                api,
                LogFiles.forGameDirectory(gameDirectory),
                new UploadHistory(dataDirectory.resolve("history.json"))
        );

        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) -> AlaLoggerCommand.register(event.getDispatcher(), service));

        // Fetch the server's limits once the game is up rather than during
        // initialisation: a network call while mods are still loading delays
        // startup for something no one has asked for yet.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            service.warmUp();

            if (config.crashWatch) {
                announceCrashes(new CrashWatch(
                        dataDirectory.resolve("crash-marker.json"),
                        LogFiles.forGameDirectory(gameDirectory)));
            }
        });

        // Let go of the HTTP client and its threads while there is still a
        // shutdown to do it in, so an upload in flight gets its grace period
        // instead of dying with the process.
        //
        // Dedicated servers only, for the same reason as on Fabric: on a client
        // this fires every time a singleplayer world closes, and the client is
        // built once per launch — closing it there would leave the next world
        // with a dead uploader.
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (event.getServer().isDedicatedServer()) {
                api.close();
            }
        });

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
     * <p>{@code Config} never fails a server over a broken config file, but the
     * URL check lives in the client's builder — so a missing {@code https://}
     * would throw out of the constructor and the loader would refuse to start the
     * mod at all. Losing log uploads because of a typo is a fair trade; losing
     * the server is not.
     */
    private static AlaLoggerApi buildApi(Config config, String minecraftVersion) {
        String userAgent = AlaLoggerApi.userAgent(BuildInfo.version(), minecraftVersion, "neoforge");

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

    /**
     * The running Minecraft version, for the User-Agent we identify ourselves with.
     *
     * <p>Read from the game rather than from the loader, unlike the Fabric side
     * which asks its own mod container. {@code SharedConstants} is vanilla and
     * says the same thing on every platform, which is one fewer place for the two
     * entrypoints to disagree.
     */
    private static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    public static Config config() {
        return config;
    }

    public static UploadService service() {
        return service;
    }
}
