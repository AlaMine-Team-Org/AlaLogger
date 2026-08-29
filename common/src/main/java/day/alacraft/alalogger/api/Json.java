package day.alacraft.alalogger.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reading fields out of JSON somebody else produced.
 *
 * <p>Field by field rather than binding to the records, because the answer is
 * not always the answer we asked for: a proxy can return an HTML error page with
 * a 200, a future version can add a field or send {@code null} where a string is
 * documented. Every accessor here answers with a fallback instead of throwing,
 * so one unexpected value cannot turn a successful upload into a crash.
 *
 * <p>Gson is {@code compileOnly}: Minecraft bundles it, so this costs nothing at
 * runtime and adds nothing to the jar.
 */
final class Json {

    private Json() {
    }

    /** The body as an object, or {@code null} when it is not a JSON object at all. */
    static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonElement parsed = JsonParser.parseString(body);

            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** The string at {@code key}, or {@code ""} when absent, null or not a primitive. */
    static String string(JsonObject json, String key) {
        JsonElement element = element(json, key);

        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    /** The string at {@code key}, empty when absent, null or blank. */
    static Optional<String> optionalString(JsonObject json, String key) {
        String value = string(json, key);

        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    static long longValue(JsonObject json, String key, long fallback) {
        JsonElement element = element(json, key);

        try {
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int intValue(JsonObject json, String key, int fallback) {
        return (int) longValue(json, key, fallback);
    }

    static OptionalInt optionalInt(JsonObject json, String key) {
        JsonElement element = element(json, key);

        if (element == null || !element.isJsonPrimitive()) {
            return OptionalInt.empty();
        }

        try {
            return OptionalInt.of(element.getAsInt());
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    static boolean boolValue(JsonObject json, String key, boolean fallback) {
        JsonElement element = element(json, key);

        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    /** The array at {@code key}, or an empty one — callers iterate either way. */
    static JsonArray array(JsonObject json, String key) {
        JsonElement element = element(json, key);

        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /** The object at {@code key}, or {@code null}; only the record factories read this. */
    static JsonObject object(JsonObject json, String key) {
        JsonElement element = element(json, key);

        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonElement element(JsonObject json, String key) {
        if (json == null || !json.has(key)) {
            return null;
        }

        JsonElement element = json.get(key);

        // JSON null is a value, not an absence — every "may be null" field in the
        // contract (expires, line, retry_after) arrives this way.
        return element == null || element.isJsonNull() ? null : element;
    }
}
