package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * A stored log: the link to hand the player, and what the site made of it.
 *
 * <p>There is no {@code success} field, though the wire has one. It is always
 * {@code true} here — anything else completes the future with an {@link
 * ApiException} instead — and a field that can only hold one value is a field
 * every caller has to read and none can learn from.
 *
 * @param id        the short public id, e.g. {@code aB3xY9kM}.
 * @param url       the human page. This is the line that goes in chat.
 * @param raw       the plain-text body, for tooling rather than people.
 * @param token     the delete token. <b>Returned exactly once</b> — the site keeps only its
 *                  hash, so a token not saved now is an upload that can never be taken back.
 * @param truncated true when the log exceeded a limit and only the first part was stored.
 *                  Worth telling the player: the findings then describe the stored part, and
 *                  for a running server's latest.log the interesting end is the part missing.
 * @param size      stored size in bytes, after redaction and truncation.
 * @param lines     stored line count.
 * @param errors    lines the parser read as errors — a blunt count, not the findings.
 * @param detected  what the site read the log as.
 * @param insights  recognised problems, already in the requested language.
 */
public record UploadResult(
        String id,
        String url,
        String raw,
        String token,
        boolean truncated,
        long size,
        int lines,
        int errors,
        Detected detected,
        List<Insight> insights) {

    public UploadResult {
        id = id == null ? "" : id;
        url = url == null ? "" : url;
        raw = raw == null ? "" : raw;
        token = token == null ? "" : token;
        detected = detected == null ? Detected.NOTHING : detected;
        insights = insights == null ? List.of() : List.copyOf(insights);
    }

    /** True when the log can still be deleted — i.e. the token came back and was kept. */
    public boolean isDeletable() {
        return !token.isBlank();
    }

    static UploadResult from(JsonObject json) {
        return new UploadResult(
                Json.string(json, "id"),
                Json.string(json, "url"),
                Json.string(json, "raw"),
                Json.string(json, "token"),
                Json.boolValue(json, "truncated", false),
                Json.longValue(json, "size", 0L),
                Json.intValue(json, "lines", 0),
                Json.intValue(json, "errors", 0),
                Detected.from(Json.object(json, "detected")),
                Insight.listFrom(Json.array(json, "insights")));
    }
}
