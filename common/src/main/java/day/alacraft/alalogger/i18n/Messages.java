package day.alacraft.alalogger.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alalogger.AlaLogger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every line this mod prints, in the language of whoever is reading it.
 *
 * <p>A handful of hardcoded English strings is the usual answer, and for a tool
 * whose audience is "anyone whose server just broke" it is a poor one. The site
 * we upload to already speaks seven languages, so the chat line pointing at it
 * should too.
 *
 * <p><b>Why not vanilla translatable components.</b> A {@code Component.translatable}
 * is resolved on the client against the client's resource packs, so the player
 * would only see real text if they had this mod installed too. Ala Logger is a
 * server-side tool used by admins on vanilla clients, so the text is resolved
 * here, on the server, against the player's declared language and sent as a
 * plain literal. It costs us this class and buys correct output for every
 * player, modded or not.
 */
public final class Messages {

    /** The languages shipped with the mod — the same seven the site serves. */
    public static final String[] SUPPORTED = {"en_us", "ru_ru", "uk_ua", "de_de", "fr_fr", "es_es", "ja_jp"};

    private static final String FALLBACK = "en_us";

    private static final Map<String, Map<String, String>> BUNDLES = new HashMap<>();

    private Messages() {
    }

    /**
     * Resolve a key for a language, falling back to English and finally to the
     * key itself — a missing translation must degrade to something readable,
     * never to an empty chat line.
     *
     * @param language a Minecraft language code such as {@code ru_ru}, or null
     * @param args     placeholder values, as alternating name/value pairs
     */
    public static String get(String language, String key, Object... args) {
        String resolved = lookup(normalise(language), key);

        if (resolved == null) {
            resolved = lookup(FALLBACK, key);
        }

        if (resolved == null) {
            // The key itself: visibly wrong, but it names what is missing, which
            // an empty string does not.
            AlaLogger.LOGGER.warn("Missing translation for '{}'", key);
            return key;
        }

        return format(resolved, args);
    }

    /**
     * Map whatever the client reports onto a bundle we actually ship.
     *
     * <p>Clients send codes we do not translate ({@code pt_br}) and regional
     * variants of ones we do ({@code de_at}). The language half is what matters,
     * so {@code de_at} finds the German bundle instead of silently falling back
     * to English.
     */
    static String normalise(String language) {
        if (language == null || language.isBlank()) {
            return FALLBACK;
        }

        String code = language.toLowerCase(Locale.ROOT).replace('-', '_').trim();

        for (String supported : SUPPORTED) {
            if (supported.equals(code)) {
                return supported;
            }
        }

        String prefix = code.contains("_") ? code.substring(0, code.indexOf('_')) : code;
        for (String supported : SUPPORTED) {
            if (supported.startsWith(prefix + "_")) {
                return supported;
            }
        }

        return FALLBACK;
    }

    /**
     * The locale segment to put in a log's URL, so a Russian player is handed
     * {@code alacraft.day/ru/logs/...} rather than the English page. The site
     * uses two-letter codes, and Ukrainian is {@code uk} there — not {@code ua}.
     */
    public static String siteLocale(String language) {
        return switch (normalise(language)) {
            case "ru_ru" -> "ru";
            case "uk_ua" -> "uk";
            case "de_de" -> "de";
            case "fr_fr" -> "fr";
            case "es_es" -> "es";
            case "ja_jp" -> "ja";
            default -> "en";
        };
    }

    /**
     * Replace {@code {name}} placeholders with the supplied values.
     *
     * <p>Named rather than positional, because translators reorder clauses: in
     * some languages the file name comes before the size and in others after,
     * and {@code %s} would silently swap them.
     */
    private static String format(String template, Object... args) {
        if (args.length == 0) {
            return template;
        }

        String out = template;
        for (int i = 0; i + 1 < args.length; i += 2) {
            out = out.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return out;
    }

    private static String lookup(String language, String key) {
        return bundle(language).get(key);
    }

    private static synchronized Map<String, String> bundle(String language) {
        return BUNDLES.computeIfAbsent(language, Messages::load);
    }

    private static Map<String, String> load(String language) {
        Map<String, String> entries = new LinkedHashMap<>();
        String path = "/assets/" + AlaLogger.MOD_ID + "/lang/" + language + ".json";

        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                return entries;
            }

            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    entries.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            AlaLogger.LOGGER.warn("Could not read the {} translations ({}).", language, e.getMessage());
        }

        return entries;
    }
}
