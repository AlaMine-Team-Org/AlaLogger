package day.alacraft.alalogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's settings, as a flat JSON file the player can read and edit.
 *
 * <p>Flat and small on purpose: a log-sharing command that needs configuring has
 * already failed. Everything here either points the mod at a different site, or
 * turns off a behaviour somebody might reasonably object to.
 *
 * <p>Missing keys fall back to the default and the file is rewritten with them,
 * so upgrading the mod never leaves a stale file half-read.
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bumped only when a key changes meaning, not when one is added. */
    public static final int SCHEMA_VERSION = 1;

    /** Where logs are uploaded. Point it at a self-hosted instance if you run one. */
    public String apiBaseUrl = AlaLogger.DEFAULT_API_BASE_URL;

    /**
     * Optional personal API token from https://alacraft.day/profile.
     *
     * <p>With it, uploads are attached to that account and get a private rate
     * limit instead of one shared with every other server on the same IP —
     * which is what makes this usable on shared hosting.
     */
    public String apiToken = "";

    /**
     * Message language: {@code auto} follows each player's own client language,
     * or pin one of en/ru/uk/de/fr/es/ja.
     *
     * <p>Console output always uses the pinned value, falling back to English —
     * a console has no player to ask.
     */
    public String language = "auto";

    /** How many detected problems to print in chat after an upload. 0 turns it off. */
    public int insightsInChat = 3;

    /**
     * Notice new crash reports on startup and offer to upload them.
     *
     * <p>Offer, never upload: a crash report leaves this machine only when a
     * human asks for it.
     */
    public boolean crashWatch = true;

    /**
     * Remember uploaded ids and their delete tokens across restarts.
     *
     * <p>Kept in memory, they would not survive a restart, and the ability to
     * delete your own log would go with them. Surviving is the whole feature.
     */
    public boolean persistHistory = true;

    /** Announce successful uploads to other online admins, as vanilla commands do. */
    public boolean broadcastToAdmins = true;

    public static Config load(Path file) {
        Config config = new Config();

        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

                config.apiBaseUrl = string(json, "apiBaseUrl", config.apiBaseUrl);
                config.apiToken = string(json, "apiToken", config.apiToken);
                config.language = string(json, "language", config.language);
                config.insightsInChat = integer(json, "insightsInChat", config.insightsInChat);
                config.crashWatch = bool(json, "crashWatch", config.crashWatch);
                config.persistHistory = bool(json, "persistHistory", config.persistHistory);
                config.broadcastToAdmins = bool(json, "broadcastToAdmins", config.broadcastToAdmins);
            }

            config.normalise();
            config.save(file);
        } catch (Exception e) {
            // A broken config must not stop a server from starting. Defaults are
            // safe and the file is left untouched so the player can fix it.
            AlaLogger.LOGGER.warn("Could not read {} ({}). Using defaults; the file was left as it is.",
                    file, e.getMessage());
            config.normalise();
        }

        return config;
    }

    public void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("apiBaseUrl", apiBaseUrl);
        json.addProperty("apiToken", apiToken);
        json.addProperty("language", language);
        json.addProperty("insightsInChat", insightsInChat);
        json.addProperty("crashWatch", crashWatch);
        json.addProperty("persistHistory", persistHistory);
        json.addProperty("broadcastToAdmins", broadcastToAdmins);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
        PrivateFiles.restrictToOwner(file);
    }

    /** True when uploads should be attached to an account. */
    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Repair values that would otherwise fail far from their cause — an empty
     * base URL surfacing as a confusing HTTP error, a negative count as an
     * exception while printing chat lines.
     */
    private void normalise() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = AlaLogger.DEFAULT_API_BASE_URL;
        }
        apiBaseUrl = apiBaseUrl.trim();
        while (apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        }

        apiToken = apiToken == null ? "" : apiToken.trim();
        language = language == null || language.isBlank() ? "auto" : language.trim().toLowerCase();
        insightsInChat = Math.max(0, Math.min(10, insightsInChat));
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
