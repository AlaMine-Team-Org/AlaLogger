package day.alacraft.alalogger.forge;

import day.alacraft.alalogger.mc.client.CrashOfferClient;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * Forge client hooks: the crash offer on the title screen.
 *
 * <p>Called from {@link AlaLoggerForge} on the physical client only. Forge has no
 * per-side mod entrypoint, so the main one checks the side and this class is
 * never loaded on a dedicated server, which has no screen classes.
 *
 * <p>Two shared calls and nothing else, like its counterparts
 * {@code AlaLoggerFabricClient} and {@code AlaLoggerNeoForgeClient};
 * {@code checkLoaderParity} compares the three.
 */
final class AlaLoggerForgeClient {

    static void init() {
        CrashOfferClient.init();

        ScreenEvent.Init.Post.BUS.addListener(
                event -> CrashOfferClient.decorate(event.getScreen(), event::addListener));
    }
}
