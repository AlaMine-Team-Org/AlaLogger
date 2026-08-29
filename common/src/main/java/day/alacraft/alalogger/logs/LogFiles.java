package day.alacraft.alalogger.logs;

import day.alacraft.alalogger.AlaLogger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Finds the files a player is allowed to share, and refuses everything else.
 *
 * <p>Two decisions here are worth stating outright.
 *
 * <p><b>It looks where the JVM actually crashes.</b> Their scan covers
 * {@code logs/}, {@code crash-reports/} and {@code debug/} — the three folders
 * Minecraft writes to. But when the JVM itself dies it never gets as far as
 * Minecraft's logging: HotSpot writes {@code hs_err_pid<PID>.log} into the
 * <em>process working directory</em>, falls back to {@code java.io.tmpdir} if
 * that is not writable, and obeys {@code -XX:ErrorFile} over both. None of
 * those are inside the game directory on a typical launcher, so a scan of the
 * three game folders finds nothing at all after the crashes that hurt most —
 * a GPU driver fault, a native out-of-memory, a bad JVM. This class reads the
 * running JVM's own arguments to find them.
 *
 * <p><b>The traversal check compares resolved paths, not names.</b> The file
 * name arrives as a command argument typed by a player, so
 * {@code /alog share ../../../etc/shadow} is not a hypothetical. Every
 * candidate is resolved with {@link Path#toRealPath} — following symlinks —
 * and its parent must equal the resolved allowed directory. Comparing after
 * resolution is what makes a symlink planted inside {@code logs/} fail too.
 * Resolving only the parent is not enough: a link sitting in the log folder and
 * pointing at an arbitrary file would sail through such a check.
 *
 * <p>Instances are immutable and cheap; the disk is read on every call, because
 * a log directory changes under us constantly and a cached listing would show
 * a player files that are already gone.
 */
public final class LogFiles {

    /**
     * The whitelist for ordinary log files.
     *
     * <p>Deliberately narrow. Everything Minecraft and the loaders write ends in
     * {@code .log} or {@code .txt}, optionally with log4j's rotation counter and
     * optionally gzipped ({@code latest.log}, {@code 2026-08-28-1.log.gz},
     * {@code crash-2026-08-28_15.10.58-server.txt}). Anything else in those
     * folders — a stray {@code .json}, an editor's swap file, a world backup
     * somebody parked there — is not a log, and refusing to upload it costs a
     * player nothing while removing a whole class of accidental disclosure.
     */
    private static final Pattern LOG_NAME = Pattern.compile(
            ".*\\.(?:log|txt)(?:\\.\\d+){0,2}(?:\\.gz)?", Pattern.CASE_INSENSITIVE);

    /**
     * HotSpot's fatal error log, named {@code hs_err_pid<PID>.log}.
     *
     * <p>Matched by name anywhere it is found, and matched loosely: the digits
     * come from {@code %p} in a template a player can override, and the exact
     * shape has drifted between JVM vendors before.
     */
    private static final Pattern HS_ERR_NAME = Pattern.compile(
            "hs_err_pid.*\\.log(?:\\.gz)?", Pattern.CASE_INSENSITIVE);

    /** The log Minecraft is writing right now, and what {@code /alalogger} means by default. */
    private static final String CURRENT_LOG = "latest.log";

    /**
     * Newest first — the file somebody wants to share is almost always the last
     * one written — with one tie broken deliberately.
     *
     * <p>Minecraft writes {@code latest.log} and {@code debug.log} in the same
     * operation, so their timestamps are identical down to the filesystem's
     * resolution — and on a plain alphabetical tie-break {@code debug.log} wins
     * every time. It is the wrong answer twice over: the mod says it uploads
     * {@code latest.log}, and {@code debug.log} is an order of magnitude larger,
     * so the file that gets truncated against the size limit is the one nobody
     * asked for. When the clock cannot separate them, the current log wins.
     */
    private static final Comparator<LogFile> NEWEST_FIRST =
            Comparator.comparing(LogFile::modified).reversed()
                    .thenComparing(file -> CURRENT_LOG.equalsIgnoreCase(file.name()) ? 0 : 1)
                    .thenComparing(LogFile::name);

    private static final String ERROR_FILE_FLAG = "-XX:ErrorFile=";

    /** One directory to scan, with the name filter that applies inside it. */
    private record Source(Path directory, LogFileType type, Predicate<String> accepts) {

        /**
         * The source directory with symlinks resolved, or null if it cannot be
         * resolved right now.
         *
         * <p>Computed per call rather than cached in a field: a record is a
         * value, and the directory can appear or disappear between two listings
         * — `logs/` does not exist until the server writes its first line. It is
         * one syscall per directory instead of one per file, which is where the
         * cost actually was.
         */
        Path realDirectory() {
            try {
                return directory.toRealPath();
            } catch (IOException | SecurityException e) {
                return null;
            }
        }
    }

    private final List<Source> sources;

    /**
     * Builds the scan for a game directory, asking the running JVM where it
     * would write a fatal error log.
     *
     * @param gameDirectory the root that contains {@code logs/}, i.e. the
     *                      {@code .minecraft} folder or the server root
     */
    public static LogFiles forGameDirectory(Path gameDirectory) {
        Path workingDirectory = systemPath("user.dir", gameDirectory);
        Path temporaryDirectory = systemPath("java.io.tmpdir", null);

        List<Path> jvmCrashDirectories = new ArrayList<>();
        jvmCrashDirectories.add(workingDirectory);
        if (temporaryDirectory != null) {
            jvmCrashDirectories.add(temporaryDirectory);
        }

        Path errorFile = errorFileFrom(jvmArguments(), workingDirectory).orElse(null);
        return new LogFiles(gameDirectory, jvmCrashDirectories, errorFile);
    }

    /**
     * Builds the scan from explicit locations.
     *
     * <p>Exists so the JVM crash locations can be supplied rather than
     * discovered — by tests, and by any host that knows better than
     * {@code user.dir} where its instances live.
     *
     * @param gameDirectory       root containing {@code logs/}, {@code crash-reports/}, {@code debug/}
     * @param jvmCrashDirectories directories that may hold {@code hs_err_pid*.log}
     * @param errorFile           the resolved {@code -XX:ErrorFile} template, or {@code null};
     *                            its file name may still contain {@code %p}
     */
    public LogFiles(Path gameDirectory, Collection<Path> jvmCrashDirectories, Path errorFile) {
        Map<Path, Source> byDirectory = new LinkedHashMap<>();

        Predicate<String> logName = name -> LOG_NAME.matcher(name).matches();
        Predicate<String> crashName = name -> HS_ERR_NAME.matcher(name).matches();

        add(byDirectory, gameDirectory.resolve("logs"), LogFileType.LOG, logName);
        add(byDirectory, gameDirectory.resolve("crash-reports"), LogFileType.CRASH_REPORT, logName);
        add(byDirectory, gameDirectory.resolve("debug"), LogFileType.NETWORK_REPORT, logName);

        // The game root is a JVM crash location in its own right: for a dedicated
        // server and for most launchers it *is* the process working directory,
        // and when it is not, an hs_err still occasionally lands there.
        add(byDirectory, gameDirectory, LogFileType.JVM_CRASH, crashName);

        if (jvmCrashDirectories != null) {
            for (Path directory : jvmCrashDirectories) {
                if (directory != null) {
                    add(byDirectory, directory, LogFileType.JVM_CRASH, crashName);
                }
            }
        }

        if (errorFile != null && errorFile.getParent() != null) {
            // -XX:ErrorFile can rename the file as well as move it, so matching
            // hs_err_pid*.log in that directory is not enough — the template has
            // to be turned into a name filter of its own.
            Pattern configured = errorFileNamePattern(errorFile.getFileName().toString());
            add(byDirectory, errorFile.getParent(), LogFileType.JVM_CRASH,
                    crashName.or(name -> configured.matcher(name).matches()));
        }

        this.sources = List.copyOf(byDirectory.values());
    }

    /** Every shareable file, newest first. */
    public List<LogFile> list() {
        return list(null);
    }

    /**
     * Every shareable file whose name contains {@code filter}, newest first.
     *
     * <p>The filter is a plain case-insensitive substring, not a glob: it exists
     * so somebody staring at forty rotated logs can type {@code 08-28} and see
     * three, and a player should not have to know what a glob is to do that.
     * A {@code null} or blank filter matches everything.
     */
    public List<LogFile> list(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        // Keyed by resolved path so the same file reachable through two sources
        // — user.dir being the game directory is the normal case — is listed once.
        Map<Path, LogFile> found = new LinkedHashMap<>();

        for (Source source : sources) {
            if (!Files.isDirectory(source.directory())) {
                continue;
            }

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(source.directory())) {
                for (Path entry : entries) {
                    if (!needle.isEmpty()
                            && !entry.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    resolve(entry, source).ifPresent(file -> found.putIfAbsent(file.path(), file));
                }
            } catch (IOException e) {
                // An unreadable directory must not cost the player the other two.
                // Common enough to be routine: a panel host that mounts logs/
                // read-only, or a crash-reports/ owned by another user.
                AlaLogger.LOGGER.debug("Could not list {}: {}", source.directory(), e.toString());
            }
        }

        List<LogFile> result = new ArrayList<>(found.values());
        result.sort(NEWEST_FIRST);
        return List.copyOf(result);
    }

    /**
     * Resolves a name typed into a command to a file that is safe to upload.
     *
     * <p>Returns empty for anything that does not exist, is not a regular file,
     * is not on the name whitelist, or resolves outside the allowed directories
     * — the caller cannot tell those apart on purpose, because "no log named
     * {@code x}" is the honest answer to all of them and a more specific message
     * would confirm to a probing player what does exist elsewhere on the disk.
     */
    public Optional<LogFile> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        for (Source source : sources) {
            Path candidate;
            try {
                candidate = source.directory().resolve(name);
            } catch (InvalidPathException e) {
                // The argument is not expressible as a path on this OS at all
                // (a colon or a wildcard on Windows). No directory will accept it.
                return Optional.empty();
            }

            Optional<LogFile> file = resolve(candidate, source);
            if (file.isPresent()) {
                return file;
            }
        }

        return Optional.empty();
    }

    /** The most recently written shareable file, if there is one. */
    public Optional<LogFile> latest() {
        return list().stream().findFirst();
    }

    /**
     * The most recently written file of one of the given types.
     *
     * <p>This is what makes {@code /alog crash} possible without an argument.
     * Demanding the exact file name would mean reading it off the disk first —
     * precisely what somebody staring at a dead server cannot do.
     */
    public Optional<LogFile> latest(LogFileType... types) {
        List<LogFileType> wanted = List.of(types);
        return list().stream().filter(file -> wanted.contains(file.type())).findFirst();
    }

    /** The directories being scanned, in order. Diagnostics only. */
    public List<Path> directories() {
        return sources.stream().map(Source::directory).toList();
    }

    /**
     * The containment check, applied identically to a listed entry and to a
     * typed argument so that everything {@code /alog list} shows can actually
     * be shared, and nothing else can.
     */
    private Optional<LogFile> resolve(Path candidate, Source source) {
        // Reject by name BEFORE touching the filesystem. toRealPath() is a
        // syscall per file, and one of the sources is java.io.tmpdir: on a
        // developer's machine that is tens of thousands of files, all but one of
        // them irrelevant. Resolving each of them first made a plain `list` take
        // ten seconds — long enough to be a visible server freeze, since this
        // also backs tab-completion.
        //
        // Safety is unaffected: the name is checked again after resolution, so a
        // symlink whose target has a different name is still rejected below.
        Path candidateName = candidate.getFileName();

        if (candidateName == null || !source.accepts().test(candidateName.toString())) {
            return Optional.empty();
        }

        try {
            // Resolve the file itself, not just its parent: a symlink sitting in
            // logs/ and pointing at /etc/shadow has the right parent but the
            // wrong target, and only full resolution catches that.
            Path real = candidate.toRealPath();
            Path directory = source.realDirectory();

            if (directory == null || !directory.equals(real.getParent())) {
                return Optional.empty();
            }

            String name = real.getFileName().toString();
            if (!source.accepts().test(name)) {
                return Optional.empty();
            }

            BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return Optional.empty();
            }

            // A JVM error log is typed by its name wherever it was found, because
            // -XX:ErrorFile can put one in logs/, where the directory would
            // otherwise label it a plain log and read it from the wrong end.
            LogFileType type = HS_ERR_NAME.matcher(name).matches() ? LogFileType.JVM_CRASH : source.type();

            return Optional.of(new LogFile(
                    real, name, type, attributes.size(), attributes.lastModifiedTime().toInstant()));
        } catch (IOException | InvalidPathException | SecurityException e) {
            // Missing, unreadable, or outside what this process may look at.
            // All three mean "not shareable", which is all the caller needs.
            return Optional.empty();
        }
    }

    private static void add(Map<Path, Source> sources, Path directory, LogFileType type, Predicate<String> accepts) {
        Path key;
        try {
            key = directory.toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException e) {
            return;
        }

        Source existing = sources.get(key);
        if (existing == null) {
            sources.put(key, new Source(key, type, accepts));
        } else if (existing.type() == type) {
            // Same directory reached twice — user.dir being the game root, or an
            // ErrorFile pointed back at it. Widen the filter instead of dropping
            // one of the two name patterns.
            sources.put(key, new Source(key, type, existing.accepts().or(accepts)));
        }
        // Different types on one directory (an ErrorFile aimed into logs/) keep
        // the first source; the file is still found, and resolve() re-types it
        // by name so it is still read from the head.
    }

    /**
     * Reads {@code -XX:ErrorFile} out of a JVM argument list.
     *
     * <p>Package-private rather than private so the parsing can be tested
     * without a JVM that was actually started with the flag.
     *
     * <p>The last occurrence wins, as HotSpot does with repeated flags, and a
     * relative template is resolved against the working directory, because that
     * is what the JVM resolves it against.
     */
    static Optional<Path> errorFileFrom(List<String> jvmArguments, Path workingDirectory) {
        if (jvmArguments == null) {
            return Optional.empty();
        }

        for (int i = jvmArguments.size() - 1; i >= 0; i--) {
            String argument = jvmArguments.get(i);
            if (argument == null || !argument.startsWith(ERROR_FILE_FLAG)) {
                continue;
            }

            String value = argument.substring(ERROR_FILE_FLAG.length()).trim();
            if (value.isEmpty()) {
                continue;
            }

            try {
                Path path = Path.of(value);
                if (path.isAbsolute()) {
                    return Optional.of(path.normalize());
                }
                return Optional.of(workingDirectory.resolve(path).normalize());
            } catch (InvalidPathException e) {
                AlaLogger.LOGGER.debug("Ignoring unusable -XX:ErrorFile value {}", value);
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    /**
     * Turns an {@code -XX:ErrorFile} file-name template into a matcher.
     *
     * <p>{@code %p} is the pid — the only token whose expansion we can pin down,
     * so it becomes a digit run. Everything else HotSpot substitutes ({@code %t}
     * and friends) becomes a wildcard rather than a guess at its format; being
     * slightly wide inside a directory the player explicitly nominated for crash
     * files is the safe direction to be wrong in.
     */
    private static Pattern errorFileNamePattern(String template) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();

        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);

            if (current == '%' && i + 1 < template.length()) {
                char token = template.charAt(++i);
                if (token == '%') {
                    literal.append('%');
                    continue;
                }
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(token == 'p' ? "\\d+" : ".*");
            } else {
                literal.append(current);
            }
        }

        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }

        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static List<String> jvmArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable e) {
            // java.management is absent from a jlinked runtime, and reading the
            // arguments can be denied outright. Losing the ErrorFile location
            // only costs us one of four places to look, so it is never fatal.
            AlaLogger.LOGGER.debug("Could not read the JVM arguments: {}", e.toString());
            return List.of();
        }
    }

    private static Path systemPath(String property, Path fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return fallback;
        }
    }
}
