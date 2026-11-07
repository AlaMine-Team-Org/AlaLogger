package day.alacraft.alalogger.fabric;

import day.alacraft.alalogger.mc.client.CrashOfferClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/**
 * Fabric client entrypoint: the crash offer on the title screen.
 *
 * <p>Two shared calls and nothing else, like its counterparts
 * {@code AlaLoggerNeoForgeClient} and {@code AlaLoggerForgeClient};
 * {@code checkLoaderParity} compares the three.
 */
public class AlaLoggerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CrashOfferClient.init();

        ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> CrashOfferClient.decorate(screen, Screens.getWidgets(screen)::add));
    }
}
