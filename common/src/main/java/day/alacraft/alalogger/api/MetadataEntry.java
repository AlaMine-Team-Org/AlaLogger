package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;

/**
 * One piece of context to keep alongside the log — a server name, a mod count,
 * the build that uploaded it.
 *
 * <p><b>Never redacted.</b> The site strips secrets out of log text because it
 * scanned that text and knows it may contain them by accident; metadata is what
 * the client chose to attach, so it is stored exactly as sent. Nothing here may
 * be a token, a password or an IP address.
 *
 * <p>Values are strings even for numbers. The API accepts numbers and booleans
 * too, but everything here ends up rendered on a web page, and one type means
 * one code path instead of a type tag nobody would read.
 *
 * @param key     identifier, at most 64 characters.
 * @param value   at most 500 characters.
 * @param label   shown on the log page instead of the key; empty to show the key.
 * @param visible false keeps the entry off the public page — it stays in the API response,
 *                so this is tidiness, not privacy.
 */
public record MetadataEntry(String key, String value, String label, boolean visible) {

    public MetadataEntry {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value;
        label = label == null ? "" : label;
    }

    /** The common case: a visible entry labelled with its own key. */
    public static MetadataEntry of(String key, String value) {
        return new MetadataEntry(key, value, "", true);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("key", key);
        json.addProperty("value", value);

        if (!label.isEmpty()) {
            json.addProperty("label", label);
        }

        if (!visible) {
            json.addProperty("visible", false);
        }

        return json;
    }
}
