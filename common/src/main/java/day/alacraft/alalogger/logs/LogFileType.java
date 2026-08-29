package day.alacraft.alalogger.logs;

/**
 * What kind of file we are looking at, which decides how it is read and how it
 * is grouped in {@code /alog list}.
 *
 * <p>The type is derived from where the file was found rather than from its
 * contents: Minecraft keeps each kind in its own directory, and sniffing the
 * first bytes of every file in {@code logs/} just to label a list would cost an
 * open per entry for information the directory already gives us. The single
 * exception is {@link #JVM_CRASH}, recognised by name wherever it turns up,
 * because {@code -XX:ErrorFile} lets a player drop one anywhere — including
 * into {@code logs/}, where the directory would otherwise call it a plain log
 * and read it from the wrong end.
 */
public enum LogFileType {

    /** {@code logs/} — {@code latest.log} and its rotated, often gzipped, siblings. */
    LOG(ReadMode.TAIL),

    /** {@code crash-reports/} — Minecraft's own {@code crash-*.txt}. */
    CRASH_REPORT(ReadMode.HEAD),

    /** {@code debug/} — network protocol error reports ({@code disconnect-*.txt}). */
    NETWORK_REPORT(ReadMode.HEAD),

    /**
     * {@code hs_err_pid*.log} — the JVM's own fatal error log.
     *
     * <p>The reason this mod exists. These never land in {@code logs/}: the JVM
     * writes them to its working directory, so no log-sharing tool that only
     * scans the game's three log folders can see them at all. They are also the
     * one file type that carries a Minecraft session token in plain text, which
     * is why the redaction layer has to run over them before anything is sent.
     */
    JVM_CRASH(ReadMode.HEAD);

    private final ReadMode readMode;

    LogFileType(ReadMode readMode) {
        this.readMode = readMode;
    }

    /** Which end of this kind of file to keep when it exceeds the upload limits. */
    public ReadMode readMode() {
        return readMode;
    }
}
