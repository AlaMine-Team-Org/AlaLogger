package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;

import java.time.Duration;

/**
 * What the target instance will accept and for how long it keeps it.
 *
 * <p>Asked for rather than hardcoded so that raising a limit on the site — or
 * running a self-hosted instance with different ones — does not need a new
 * release of the mod. The defaults here match alacraft.day at the time of
 * writing and are only used when the call has not landed yet.
 *
 * @param maxLength   largest stored size in bytes. Over it, the site truncates rather than
 *                    refusing — but it keeps the <b>start</b> of the log, so for a running
 *                    server's latest.log the mod should send the tail itself.
 * @param maxLines    largest stored line count, applied the same way.
 * @param storageTime how long a log survives, counted from the last time a human opened it.
 */
public record Limits(long maxLength, int maxLines, Duration storageTime) {

    /** alacraft.day's published values: 10 MiB, 25 000 lines, 90 days. */
    public static final Limits DEFAULTS = new Limits(10L * 1024 * 1024, 25_000, Duration.ofDays(90));

    public Limits {
        storageTime = storageTime == null ? Duration.ZERO : storageTime;
    }

    /** Days a log survives without being opened — the number a player understands. */
    public long storageDays() {
        return storageTime.toDays();
    }

    static Limits from(JsonObject json) {
        return new Limits(
                Json.longValue(json, "maxLength", DEFAULTS.maxLength()),
                Json.intValue(json, "maxLines", DEFAULTS.maxLines()),
                Duration.ofSeconds(Json.longValue(json, "storageTime", DEFAULTS.storageTime().toSeconds())));
    }
}
