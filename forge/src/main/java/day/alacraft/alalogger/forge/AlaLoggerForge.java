package day.alacraft.alalogger.forge;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.AlaLoggerBootstrap;
import day.alacraft.alalogger.mc.AlaLoggerCommand;
import net.minecraft.SharedConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * MinecraftForge entrypoint.
 *
 * <p>An adapter and nothing else, exactly like its two siblings: it tells
 * {@link AlaLoggerBootstrap} where this loader keeps its directories and connects
 * three Forge events to methods shared with every other loader.
 *
 * <p>Forge's EventBus 7 puts a static {@code BUS} on each event, so listeners are
 * added there rather than on a global bus as NeoForge does.
 *
 * <p>Counterparts: {@code day.alacraft.alalogger.fabric.AlaLoggerFabric} and
 * {@code day.alacraft.alalogger.neoforge.AlaLoggerNeoForge}.
 * {@code checkLoaderParity} fails the build if one of them grows logic the others
 * do not have.
 */
@Mod(AlaLogger.MOD_ID)
public class AlaLoggerForge {

    public AlaLoggerForge(FMLJavaModLoadingContext context) {
        AlaLoggerBootstrap mod = AlaLoggerBootstrap.start(new AlaLoggerBootstrap.Platform(
                "forge", minecraftVersion(), FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get()));

        RegisterCommandsEvent.BUS.addListener(
                event -> AlaLoggerCommand.register(event.getDispatcher(), mod.service()));

        ServerStartedEvent.BUS.addListener(event -> mod.serverStarted(event.getServer().isDedicatedServer()));
        ServerStoppedEvent.BUS.addListener(
                event -> mod.serverStopped(event.getServer().isDedicatedServer()));

        // Forge has no per-side mod entrypoint. The client hooks live in their own
        // class so that a dedicated server, which has no screen classes, never
        // loads them.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            AlaLoggerForgeClient.init();
        }
    }

    /**
     * The running Minecraft version, for the User-Agent. Read from the game, as on
     * NeoForge: by the time a Forge mod is constructed the constant is populated.
     */
    private static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
