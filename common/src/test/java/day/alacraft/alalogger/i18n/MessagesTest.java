package day.alacraft.alalogger.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the eleven bundles against the two ways a translation set rots: a key
 * added to English and forgotten elsewhere, and a placeholder mistyped during
 * translation.
 *
 * <p>Both failures are invisible until a player in that language hits the exact
 * line — which, for error messages, is precisely when they are already having a
 * bad day.
 */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_]+)}");

    @Test
    void every_language_has_every_english_key() {
        JsonObject english = bundle("en_us");

        for (String language : Messages.SUPPORTED) {
            JsonObject other = bundle(language);

            for (String key : english.keySet()) {
                assertTrue(other.has(key), language + " is missing the key '" + key + "'");
            }
            for (String key : other.keySet()) {
                assertTrue(english.has(key), language + " has '" + key + "', which English does not");
            }
        }
    }

    /**
     * A translator who writes {@code {файл}} instead of {@code {file}} produces
     * a line that renders with a literal brace in it. The set of placeholder
     * names therefore has to match English exactly.
     */
    @Test
    void placeholders_match_english_in_every_language() {
        JsonObject english = bundle("en_us");

        for (String language : Messages.SUPPORTED) {
            JsonObject other = bundle(language);

            for (String key : english.keySet()) {
                assertEquals(
                        placeholders(english.get(key).getAsString()),
                        placeholders(other.get(key).getAsString()),
                        "placeholders differ in " + language + " for '" + key + "'"
                );
            }
        }
    }

    /**
     * Every other test here walks {@link Messages#SUPPORTED}, so a bundle that
     * was written but never registered is invisible to all of them: the file
     * ships inside the jar, passes review, and is handed to nobody, because
     * {@code normalise} only ever answers with a code from that array.
     */
    @Test
    void every_shipped_bundle_is_registered() throws Exception {
        Path dir = Path.of(MessagesTest.class.getResource("/assets/alalogger/lang/en_us.json").toURI())
                .getParent();

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                String language = name.substring(0, name.length() - ".json".length());
                assertTrue(
                        Set.of(Messages.SUPPORTED).contains(language),
                        language + ".json ships in the jar but is not in Messages.SUPPORTED, "
                                + "so no player is ever shown it"
                );
            }
        }
    }

    @Test
    void unknown_language_falls_back_to_english() {
        assertEquals("en_us", Messages.normalise("it_it"));
        assertEquals("en_us", Messages.normalise(null));
        assertEquals("en_us", Messages.normalise(""));
    }

    /** A client reporting de_at should read German, not English. */
    @Test
    void regional_variants_resolve_to_the_shipped_language() {
        assertEquals("de_de", Messages.normalise("de_at"));
        assertEquals("es_es", Messages.normalise("es_mx"));
        assertEquals("ru_ru", Messages.normalise("ru_ru"));
    }

    @Test
    void site_locale_matches_what_the_site_serves() {
        assertEquals("ru", Messages.siteLocale("ru_ru"));
        // The site uses `uk` for Ukrainian, not `ua` — getting this wrong sends
        // the player to a 404 instead of their own language.
        assertEquals("uk", Messages.siteLocale("uk_ua"));
        assertEquals("ja", Messages.siteLocale("ja_jp"));
        assertEquals("zh", Messages.siteLocale("zh_cn"));
        // The mod translates the chat text into Portuguese, Polish and Turkish,
        // but the site has no pages in them, so the link itself falls back to
        // English.
        assertEquals("en", Messages.siteLocale("pt_br"));
        assertEquals("en", Messages.siteLocale("pl_pl"));
        assertEquals("en", Messages.siteLocale("tr_tr"));
    }

    @Test
    void placeholders_are_filled_by_name() {
        String rendered = Messages.get("en_us", "upload.start", "file", "latest.log", "size", "2.4 MB");

        assertEquals("Uploading latest.log (2.4 MB)...", rendered);
        assertFalse(rendered.contains("{"), "no placeholder should survive");
    }

    @Test
    void a_missing_key_returns_the_key_rather_than_nothing() {
        assertEquals("no.such.key", Messages.get("en_us", "no.such.key"));
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static JsonObject bundle(String language) {
        String path = "/assets/alalogger/lang/" + language + ".json";
        try (InputStream in = MessagesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing bundle: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
