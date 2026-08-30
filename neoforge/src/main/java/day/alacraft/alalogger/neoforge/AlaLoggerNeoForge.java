package day.alacraft.alalogger.neoforge;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.AlaLoggerBootstrap;
import day.alacraft.alalogger.mc.AlaLoggerCommand;
import net.minecraft.SharedConstants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * NeoForge entrypoint.
 *
 * <p>An adapter and nothing else: it tells {@link AlaLoggerBootstrap} where this
 * loader keeps its directories and connects three NeoForge events to methods that
 * are shared with every other loader. Anything a player can see — the command
 * tree, the redaction rules, the translations, the history file — lives in
 * {@code common} and {@code common-mc}, and both jars compile it from the same
 * source.
 *
 * <p>Its counterpart is {@code day.alacraft.alalogger.fabric.AlaLoggerFabric},
 * and the two are deliberately line-for-line equivalent. {@code checkLoaderParity}
 * in the build fails if one of them grows logic the other does not have.
 */
@Mod(AlaLogger.MOD_ID)
public class AlaLoggerNeoForge {

    public AlaLoggerNeoForge(IEventBus modBus) {
        AlaLoggerBootstrap mod = AlaLoggerBootstrap.start(new AlaLoggerBootstrap.Platform(
                "neoforge", minecraftVersion(), FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get()));

        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) -> AlaLoggerCommand.register(event.getDispatcher(), mod.service()));

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> mod.serverStarted());
        NeoForge.EVENT_BUS.addListener(
                (ServerStoppedEvent event) -> mod.serverStopped(event.getServer().isDedicatedServer()));
    }

    /**
     * The running Minecraft version, for the User-Agent we identify ourselves with.
     *
     * <p>Read from the game rather than from the loader, unlike the Fabric side
     * which asks its own mod container. By the time a NeoForge mod is constructed
     * the game's version constant is populated, and it is vanilla — so it says the
     * same thing here as it would anywhere else.
     */
    private static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
