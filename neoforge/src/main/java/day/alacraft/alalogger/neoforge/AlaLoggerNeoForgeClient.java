package day.alacraft.alalogger.neoforge;

import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.mc.client.CrashOfferClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client entrypoint: the crash offer on the title screen.
 *
 * <p>Two shared calls and nothing else, like its counterparts
 * {@code AlaLoggerFabricClient} and {@code AlaLoggerForgeClient};
 * {@code checkLoaderParity} compares the three.
 */
@Mod(value = AlaLogger.MOD_ID, dist = Dist.CLIENT)
public class AlaLoggerNeoForgeClient {

    public AlaLoggerNeoForgeClient() {
        CrashOfferClient.init();

        NeoForge.EVENT_BUS.addListener(
                (ScreenEvent.Init.Post event) -> CrashOfferClient.decorate(event.getScreen(), event::addListener));
    }
}
