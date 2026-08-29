package day.alacraft.alalogger.history;

import java.time.Instant;
import java.util.Objects;

/**
 * One upload, remembered so it can be deleted later.
 *
 * <p>The {@code token} is the whole point. alacraft.day hands one back with
 * every upload, and it is the only proof that a later delete request comes from
 * whoever made the log. Held in a {@code HashMap} in memory, it would not
 * outlive the process, and restarting the server would permanently strip the
 * ability to delete anything uploaded before the restart — and a server restarts
 * precisely when something went wrong, which is exactly when logs get uploaded. Writing the token to disk is not a nicety; it is the difference
 * between the delete button working and being decorative.
 *
 * <p>Which is also why this record is a secret: see the file permissions in
 * {@link UploadHistory}.
 *
 * @param id         the short public id, also the last path segment of {@code url}
 * @param url        the viewer link handed to the player, already locale-specific
 * @param token      the delete token, or empty when the server did not issue one
 * @param uploadedAt when the upload happened, for {@code /alog history}
 * @param fileName   which file was uploaded, so a list of ids is readable
 */
public record UploadRecord(String id, String url, String token, Instant uploadedAt, String fileName) {

    public UploadRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uploadedAt, "uploadedAt");

        if (id.isBlank()) {
            throw new IllegalArgumentException("An upload record needs an id");
        }

        // The optional fields are normalised rather than rejected: a server that
        // answers without a delete token, or an upload of a stream with no file
        // name, is still worth remembering for its link.
        url = url == null ? "" : url;
        token = token == null ? "" : token;
        fileName = fileName == null ? "" : fileName;
    }

    /** Whether this upload can still be deleted from the site. */
    public boolean hasToken() {
        return !token.isBlank();
    }
}
