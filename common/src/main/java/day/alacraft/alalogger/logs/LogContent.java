package day.alacraft.alalogger.logs;

import java.util.Objects;

/**
 * The text that will be uploaded, plus what had to be left behind to fit.
 *
 * <p>{@code truncated} exists because a reader that silently drops whatever does
 * not fit leaves the player with a link and no idea that the interesting part was
 * never sent. Carrying
 * the fact out of the reader is what lets the command say "the file was larger
 * than 10 MB, the last 25 000 lines were uploaded" — which is also the moment
 * to mention that the *last* lines are the ones kept.
 *
 * @param text      the content to upload, with line endings normalised to {@code \n}
 * @param lines     how many lines {@code text} actually contains
 * @param truncated whether anything was left out
 * @param mode      which end of the file was kept, so the message can say so
 */
public record LogContent(String text, int lines, boolean truncated, ReadMode mode) {

    public LogContent {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(mode, "mode");
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
