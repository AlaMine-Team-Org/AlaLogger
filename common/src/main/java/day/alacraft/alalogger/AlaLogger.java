package day.alacraft.alalogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the mod's logger.
 *
 * <p>Everything in {@code common} is deliberately free of Minecraft types: the
 * work this mod does — reading a file, stripping secrets out of it, posting it
 * over HTTP — has nothing to do with the game, and keeping it separate means it
 * can be unit-tested in milliseconds and recompiled unchanged into a Fabric mod,
 * a NeoForge mod or a Paper plugin.
 */
public final class AlaLogger {

    public static final String MOD_ID = "alalogger";

    public static final String MOD_NAME = "Ala Logger";

    /**
     * Where logs are uploaded unless the config points somewhere else.
     *
     * <p>Overridable because a self-hosted Log Checker is a legitimate setup:
     * anyone running their own instance should be able to point the mod at it.
     */
    public static final String DEFAULT_API_BASE_URL = "https://alacraft.day/api/v1";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private AlaLogger() {
    }
}
