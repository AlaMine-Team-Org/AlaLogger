package day.alacraft.alalogger.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One upload: the log text, plus who is sending it and in what language the
 * findings should come back.
 *
 * <p>{@code source} and {@code language} may be left empty, in which case the
 * client's own defaults apply. They exist per-request anyway because a mod that
 * uploads both a crash report and a server log wants those distinguishable on
 * the page, and because a player running the command should get findings in
 * their language rather than the console's.
 *
 * @param content  the raw log text. Already redacted by the site on arrival, but sending only
 *                 the tail of a large file is the client's job — the site truncates from the
 *                 start, which for a running server keeps the least interesting half.
 * @param source   client identity as {@code name/version}, e.g. {@code alalogger/1.0.0}.
 * @param language locale for the returned findings: one of en, ru, uk, de, fr, es, ja.
 * @param metadata extra context; at most 20 entries.
 */
public record UploadRequest(String content, String source, String language, List<MetadataEntry> metadata) {

    public UploadRequest {
        content = content == null ? "" : content;
        source = source == null ? "" : source.trim();
        language = language == null ? "" : language.trim();
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
    }

    /** Just a log, with the client's defaults for everything else. */
    public static UploadRequest of(String content) {
        return new UploadRequest(content, "", "", List.of());
    }

    /** A copy with the client's defaults filled in wherever this request left a blank. */
    UploadRequest withDefaults(String defaultSource, String defaultLanguage) {
        return new UploadRequest(
                content,
                source.isEmpty() ? defaultSource : source,
                language.isEmpty() ? defaultLanguage : language,
                metadata);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("content", content);

        if (!source.isEmpty()) {
            json.addProperty("source", source);
        }

        if (!metadata.isEmpty()) {
            JsonArray entries = new JsonArray();

            for (MetadataEntry entry : metadata) {
                entries.add(entry.toJson());
            }

            json.add("metadata", entries);
        }

        // `language` is deliberately absent: the site reads it from the query
        // string, not the body. See AlaLoggerApi#uploadUri.
        return json;
    }
}
