package day.alacraft.alalogger.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Message keys are string literals, so nothing compiles them.
 *
 * <p>A typo therefore survives every other test and reaches the player as the
 * literal text {@code error.no_crash_report} in the middle of their chat — at
 * the moment they are already trying to work out why their server died. The
 * command module is compiled against Minecraft and cannot be unit-tested from
 * here, so this reads its source. Weaker than calling the code, and the
 * strongest check available on this side of the loader boundary.
 */
class CommandMessageKeysTest {

    /** The command module, relative to this module's directory. */
    private static final Path COMMANDS =
            Path.of("..", "common-mc", "src", "main", "java", "day", "alacraft", "alalogger", "mc");

    /** This module's own sources: startup lines, the crash watcher, redaction names. */
    private static final Path COMMON = Path.of("src", "main", "java");

    @Test
    @DisplayName("no command asks for a key the bundle does not have")
    void everyKeyUsedByTheCommandsExists() {
        JsonObject english = bundle();
        Set<String> used = keyShapedLiterals(read(COMMANDS), english);

        // A test that found nothing to check would pass forever while the source
        // moved, was renamed, or stopped looking like this.
        assertTrue(used.size() >= 20,
                "only found " + used.size() + " message keys in " + COMMANDS.toAbsolutePath()
                        + " - the source moved or the keys changed shape, and this is no longer reading them");

        for (String key : used) {
            assertTrue(english.has(key), "the commands use '" + key + "', which no bundle defines");
        }
    }

    /**
     * Every literal in the command source that is shaped like a message key.
     *
     * <p>Shape rather than call site, because a key does not always reach
     * ChatFormat as a literal argument: the "nothing found" message is chosen by
     * the caller and passed down, and the per-operation generic keys live in an
     * enum. Both are exactly where a typo would hide from a call-site check.
     *
     * <p>"Shaped like a key" means the part before the first dot is a family the
     * bundle already has ({@code error}, {@code list}, {@code upload}...), which
     * keeps ordinary dotted strings — {@code alacraft.day}, a class name — out.
     */
    private static Set<String> keyShapedLiterals(String source, JsonObject bundle) {
        Set<String> families = new LinkedHashSet<>();

        for (String key : bundle.keySet()) {
            int dot = key.indexOf('.');
            families.add(dot < 0 ? key : key.substring(0, dot));
        }

        Set<String> found = new LinkedHashSet<>();

        for (String literal : literals(source)) {
            int dot = literal.indexOf('.');

            if (dot > 0 && families.contains(literal.substring(0, dot))) {
                found.add(literal);
            }
        }

        assertFalse(found.isEmpty(), "no message keys found in the command source");

        return found;
    }

    /** Every string literal in the source, escapes left as written. */
    private static List<String> literals(String source) {
        List<String> found = new ArrayList<>();
        int i = 0;

        while (i < source.length()) {
            if (source.charAt(i) != '"') {
                i++;
                continue;
            }

            int end = i + 1;

            while (end < source.length() && source.charAt(end) != '"') {
                end += source.charAt(end) == '\\' ? 2 : 1;
            }

            if (end < source.length()) {
                found.add(source.substring(i + 1, end));
            }

            i = end + 1;
        }

        return found;
    }

    @Test
    @DisplayName("the bundle carries no key nothing asks for")
    void everyKeyInTheBundleIsUsed() {
        // A key left behind after a message was reworded is dead weight that
        // every translator still has to carry, in seven files.
        //
        // Asked as "does this literal appear anywhere", because not every key
        // reaches ChatFormat as a call argument: the trimming notice picks
        // between two keys with a ternary, and the startup lines are printed
        // from common, on the other side of the loader boundary.
        String sources = read(COMMANDS) + read(COMMON);

        for (String key : bundle().keySet()) {
            assertTrue(sources.contains('"' + key + '"') || builtAtRuntime(key, sources),
                    "'" + key + "' is in the bundle but no source mentions it");
        }
    }

    /**
     * The one family whose keys are assembled rather than written out:
     * RedactionSummaries turns each rule's name into {@code "redaction." + name},
     * so no source contains the whole key. Named explicitly instead of accepting
     * any prefix match, which would let a stale {@code error.*} key pass because
     * some other {@code error.*} key exists.
     */
    private static boolean builtAtRuntime(String key, String sources) {
        return key.startsWith("redaction.") && sources.contains("\"redaction.\"");
    }

    private static String read(Path root) {
        assertTrue(Files.isDirectory(root), "cannot find sources at " + root.toAbsolutePath());

        StringBuilder all = new StringBuilder();

        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append(System.lineSeparator());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return all.toString();
    }

    private static JsonObject bundle() {
        String path = "/assets/alalogger/lang/en_us.json";

        try (InputStream in = CommandMessageKeysTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing bundle " + path);

            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
