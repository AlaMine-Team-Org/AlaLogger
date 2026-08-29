package day.alacraft.alalogger.logs;

/**
 * Which end of a file survives when it is larger than the upload limits.
 *
 * <p>This is the most consequential decision in the reader, and the easy answer
 * is the wrong one. Always keeping the <em>first</em> lines means a 40 MB
 * {@code latest.log} uploads its startup banner and throws away the exception at
 * the bottom — the one thing the person was trying to show somebody.
 *
 * <p>A running log grows at the end, so the end is what matters. A crash report
 * and an {@code hs_err_pid} file are written once, in one go, and state their
 * cause within the first twenty lines, so there the head is what matters.
 * Neither rule works for both, which is why the mode travels with the file type
 * instead of being a setting.
 */
public enum ReadMode {

    /** Keep the beginning of the file — crash reports state their cause there. */
    HEAD,

    /** Keep the end of the file — a live log appends the failure at the bottom. */
    TAIL
}
