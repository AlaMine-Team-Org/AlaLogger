package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * What the site worked out about the log by reading it.
 *
 * <p>Every field is optional because detection is best-effort: a truncated log,
 * or one from a launcher nobody has taught the parser about, legitimately yields
 * nothing. Worth showing back to the player anyway — "we read this as a Fabric
 * 26.2 crash report" is how they notice they uploaded the wrong file.
 *
 * @param type             {@code crash_report}, {@code jvm_crash}, {@code server_log}, {@code client_log}.
 * @param loader           {@code fabric}, {@code neoforge}, {@code forge}, {@code quilt}, {@code paper}, ...
 * @param minecraftVersion e.g. {@code 26.2}.
 * @param javaVersion      e.g. {@code 25.0.1}.
 */
public record Detected(
        Optional<String> type,
        Optional<String> loader,
        Optional<String> minecraftVersion,
        Optional<String> javaVersion) {

    /** Nothing recognised — also what a response with no {@code detected} block means. */
    public static final Detected NOTHING =
            new Detected(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public Detected {
        type = type == null ? Optional.empty() : type;
        loader = loader == null ? Optional.empty() : loader;
        minecraftVersion = minecraftVersion == null ? Optional.empty() : minecraftVersion;
        javaVersion = javaVersion == null ? Optional.empty() : javaVersion;
    }

    static Detected from(JsonObject json) {
        if (json == null) {
            return NOTHING;
        }

        return new Detected(
                Json.optionalString(json, "type"),
                Json.optionalString(json, "loader"),
                // Snake_case on the wire, because the site speaks PHP.
                Json.optionalString(json, "minecraft_version"),
                Json.optionalString(json, "java_version"));
    }
}
