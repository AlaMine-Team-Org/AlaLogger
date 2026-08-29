package day.alacraft.alalogger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Which build of the mod this is, read from a stamp baked into the jar.
 *
 * <p>This is not vanity. Every upload identifies itself to the site as
 * {@code alalogger/<version>}, and when a log arrives malformed the first
 * question is which build produced it. A version alone cannot answer that
 * during development, where a dozen builds share one version number, so the
 * commit hash travels with it.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/" + AlaLogger.MOD_ID + ".build.json";

    private static final String UNKNOWN = "dev";

    private static String version = UNKNOWN;
    private static String gitHash = UNKNOWN;
    private static String buildTime = UNKNOWN;

    static {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                version = read(json, "version");
                gitHash = read(json, "git");
                buildTime = read(json, "built");
            }
        } catch (Exception e) {
            // A missing or unreadable stamp must never stop the mod from
            // loading — it only costs traceability, and "dev" says exactly that.
            AlaLogger.LOGGER.warn("Could not read the build stamp; reporting this build as '{}'.", UNKNOWN);
        }
    }

    private BuildInfo() {
    }

    public static String version() {
        return version;
    }

    public static String gitHash() {
        return gitHash;
    }

    public static String buildTime() {
        return buildTime;
    }

    /**
     * The identity sent to the API, in {@code client/version} form. It becomes
     * the badge shown on the log page, so it has to match what the site's
     * whitelist expects.
     */
    public static String sourceTag() {
        return AlaLogger.MOD_ID + "/" + version;
    }

    private static String read(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            String value = json.get(key).getAsString().trim();
            // An unexpanded `${...}` means the resource was read straight from
            // the source tree rather than a processed build.
            if (!value.isEmpty() && !value.startsWith("${")) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
