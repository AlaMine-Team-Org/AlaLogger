package day.alacraft.alalogger.redact;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup block of real Quilt Loader 0.30.2 server logs (Minecraft 26.3 and
 * 26.2) running the Fabric jar. Quilt prints its own mod table (version, SHA-1,
 * file columns) and a plugin line that neither Fabric nor NeoForge prints, and
 * every loader so far has had a line the rules misread. The rows are verbatim;
 * only the account name and server folder in the path were changed.
 */
// @implements TC-LOG-004
class QuiltLogRedactionTest {

    private static final String HEADER = String.join("\n",
            "Starting org.quiltmc.loader.impl.game.minecraft.BundlerClassPathCapture",
            "[22:21:46] [main/INFO]: Loading Minecraft 26.3 with Quilt Loader 0.30.2",
            "[22:21:46] [main/WARN]: Not remapping mods because intermediary is missing!",
            "[22:21:50] [main/INFO]: Loading 43 mods:",
            "| Index | Mod                                    | ID                                   | Version           | Type    | File Hash (SHA-1)                        | File(s)                                                                  | Sub-File                                                       |",
            "|------:|----------------------------------------|--------------------------------------|-------------------|---------|------------------------------------------|--------------------------------------------------------------------------|----------------------------------------------------------------|",
            "|    33 | Ala Logger                             | alalogger                            | 0.2.0             | Fabric  | a04e9ab0f60cb696ad2bb219ac0543a8bcf24869 | <mods>\\alalogger-fabric-26.3-0.2.0.jar                                   |                                                                |",
            "|    14 | Fabric Resource Conditions API (v1)    | fabric-resource-conditions-api-v1    | 6.1.0+10e9a28b9e  | Fabric  | 1d22675ae6ebc304f7f808ca842b11db85ca9a90 | <mods>\\fabric-api-0.153.0+26.2.jar                                       | /META-INF/jars/fabric-resource-conditions-api-v1-6.1.0+10e9a28b9e.jar |",
            "|     2 | Minecraft                              | minecraft                            | 26.3              | Builtin | 86765a5899bd9c96461036a628796b4245715058 | <game>\\versions\\26.3\\server-26.3.jar                                     |                                                                |",
            "|    26 | MixinExtras                            | mixinextras                          | 0.5.5             | Fabric  | d1055b99c0ab08a8403fe2da3d79ca28e6340a76 | <game>\\libraries\\org\\quiltmc\\quilt-loader\\0.30.2\\quilt-loader-0.30.2.jar | /META-INF/jars/mixinextras-fabric-0.5.5.jar                    |",
            "|    28 | OpenJDK 64-Bit Server VM               | java                                 | 25                | Builtin |                                          | C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.2.10-hotspot                  |                                                                |",
            "|    39 | Quilt Loader                           | quilt_loader                         | 0.30.2            | Quilt   | d1055b99c0ab08a8403fe2da3d79ca28e6340a76 | <game>\\libraries\\org\\quiltmc\\quilt-loader\\0.30.2\\quilt-loader-0.30.2.jar |                                                                |",
            "|------:|----------------------------------------|--------------------------------------|-------------------|---------|------------------------------------------|--------------------------------------------------------------------------|----------------------------------------------------------------|",
            "Mod Table Version: 2",
            "Plugin Types: {quilted_fabric_loader=[Fabric], quilt_loader=[Quilt, Builtin]}");

    private static final String WITH_PATH =
            "[22:21:50] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7 "
                    + "Source=file:/C:/Users/Steve/servers/quilt/libraries/net/fabricmc/sponge-mixin/"
                    + "0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar Service=Knot/Fabric Env=SERVER";

    private final Redactor redactor = new Redactor();

    @Test
    void keepsTheQuiltModTableVerbatim() {
        // Versions, SHA-1 hashes and library paths are what a reader of a Quilt
        // log needs first; none of them is personal.
        RedactionResult result = redactor.redact(HEADER);

        assertEquals(HEADER, result.content());
        assertTrue(result.isClean(), () -> "masked: " + result.matchedSummary());
    }

    @Test
    void masksTheAccountNameInAQuiltStartupPath() {
        RedactionResult result = redactor.redact(HEADER + "\n" + WITH_PATH);

        assertFalse(result.content().contains("Steve"), result.content());
        assertTrue(result.content().contains("sponge-mixin-0.17.4+mixin.0.8.7.jar"), result.content());
        assertTrue(result.content().startsWith(HEADER), "the mod table must survive next to a masked path");
        assertEquals(Map.of("path", 1), result.matchedSummary());
    }
}
