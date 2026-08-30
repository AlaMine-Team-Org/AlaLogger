package day.alacraft.alalogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod's startup sequence, run without a game.
 *
 * <p>This is what moving it out of the loader modules bought. The sequence used
 * to exist twice, in a Fabric file and a NeoForge file, and could only be
 * exercised by launching Minecraft — so the one input that can realistically be
 * wrong, a hand-edited {@code apiBaseUrl}, was never tested against it, and a
 * typo in that field stopped servers from starting.
 */
class AlaLoggerBootstrapTest {

    @TempDir
    Path root;

    private Path configDirectory() {
        return root.resolve("config");
    }

    private AlaLoggerBootstrap start() {
        return AlaLoggerBootstrap.start(new AlaLoggerBootstrap.Platform(
                "fabric", "26.2", configDirectory(), root.resolve("game")));
    }

    @Test
    void buildsEverythingTheCommandsNeed() {
        AlaLoggerBootstrap mod = start();

        assertNotNull(mod.service());
        assertNotNull(mod.config());
        assertSame(mod.config(), mod.service().config(), "one config, not a copy per consumer");
        assertEquals("alacraft.day", mod.service().host());
    }

    @Test
    @DisplayName("everything the mod writes hangs off the loader's config directory")
    void keepsItsFilesTogether() {
        AlaLoggerBootstrap mod = start();

        assertTrue(Files.isRegularFile(configDirectory().resolve("alalogger.json")));
        assertEquals(configDirectory().resolve("alalogger").resolve("history.json"),
                mod.service().history().file(),
                "delete tokens must not be stranded somewhere the player never chose");
    }

    @Test
    @DisplayName("a typo in apiBaseUrl costs uploads, never the server")
    void startsWithABrokenConfig() throws IOException {
        Files.createDirectories(configDirectory());
        Files.writeString(configDirectory().resolve("alalogger.json"),
                "{\"apiBaseUrl\": \"alacraft.day/api/v1\"}", StandardCharsets.UTF_8);

        AlaLoggerBootstrap mod = start();

        assertEquals(AlaLogger.DEFAULT_API_BASE_URL, mod.config().apiBaseUrl);
        assertEquals("alacraft.day", mod.service().host());
    }

    @Test
    void startsWithAConfigThatIsNotJsonAtAll() throws IOException {
        Files.createDirectories(configDirectory());
        Files.writeString(configDirectory().resolve("alalogger.json"), "<<<", StandardCharsets.UTF_8);

        assertEquals(AlaLogger.DEFAULT_API_BASE_URL, start().config().apiBaseUrl);
    }

    @Test
    @DisplayName("a self-hosted instance is honoured, and named in the errors about it")
    void followsAConfiguredInstance() throws IOException {
        Files.createDirectories(configDirectory());
        Files.writeString(configDirectory().resolve("alalogger.json"),
                "{\"apiBaseUrl\": \"https://logs.example.org/api/v1\"}", StandardCharsets.UTF_8);

        assertEquals("logs.example.org", start().service().host());
    }

    @Test
    void shuttingDownAClientKeepsTheUploaderAlive() {
        AlaLoggerBootstrap mod = start();

        // On a client this fires every time a singleplayer world closes, and the
        // client is built once per launch: closing it here would leave the next
        // world unable to upload anything.
        mod.serverStopped(false);

        assertEquals("alacraft.day", mod.service().host());
    }

    @Test
    void refusesAPlatformItCannotUse() {
        assertThrows(NullPointerException.class, () -> new AlaLoggerBootstrap.Platform(
                "fabric", "26.2", null, root));
        assertThrows(NullPointerException.class, () -> new AlaLoggerBootstrap.Platform(
                "fabric", "26.2", root, null));

        // A missing name is not worth failing a startup over; it only ever
        // reaches a User-Agent.
        assertEquals("unknown", new AlaLoggerBootstrap.Platform("  ", "", root, root).loaderName());
        assertEquals("unknown", new AlaLoggerBootstrap.Platform("fabric", null, root, root)
                .minecraftVersion());
    }
}
