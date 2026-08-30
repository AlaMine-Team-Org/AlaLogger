package day.alacraft.alalogger.fabric;

import day.alacraft.alalogger.AlaLoggerBootstrap;
import day.alacraft.alalogger.mc.AlaLoggerCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric entrypoint.
 *
 * <p>An adapter and nothing else: it tells {@link AlaLoggerBootstrap} where this
 * loader keeps its directories and connects three Fabric events to methods that
 * are shared with every other loader. Anything a player can see — the command
 * tree, the redaction rules, the translations, the history file — lives in
 * {@code common} and {@code common-mc}, and both jars compile it from the same
 * source.
 *
 * <p>Its counterpart is {@code day.alacraft.alalogger.neoforge.AlaLoggerNeoForge},
 * and the two are deliberately line-for-line equivalent. {@code checkLoaderParity}
 * in the build fails if one of them grows logic the other does not have.
 */
public class AlaLoggerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();

        AlaLoggerBootstrap mod = AlaLoggerBootstrap.start(new AlaLoggerBootstrap.Platform(
                "fabric", minecraftVersion(loader), loader.getConfigDir(), loader.getGameDir()));

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AlaLoggerCommand.register(dispatcher, mod.service()));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> mod.serverStarted());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> mod.serverStopped(server.isDedicatedServer()));
    }

    /**
     * The running Minecraft version, for the User-Agent we identify ourselves with.
     *
     * <p>Asked of the loader rather than of {@code SharedConstants}, which the
     * NeoForge side uses: on Fabric a mod initialiser runs early enough that the
     * game's own version constant is not reliably populated yet, and the loader's
     * mod container always is.
     */
    private static String minecraftVersion(FabricLoader loader) {
        return loader.getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
