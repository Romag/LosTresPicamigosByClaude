package com.picamigos.exec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a command name (e.g. {@code codex}, {@code claude}, {@code agy}) to the actual
 * executable {@link Path} on {@code PATH}, and reports whether the resolved file must be launched
 * via a Windows shell wrapper ({@code cmd.exe /c}) because it is a {@code .cmd} or {@code .bat}
 * script (which {@link ProcessBuilder} cannot exec directly on Windows).
 *
 * <p>The public {@link #resolve(String)} method reads the real OS environment. The package-private
 * overload {@link #resolve(String, boolean, List, List)} is a pure function that accepts all
 * environment inputs as parameters, making it easy to unit-test without OS coupling.
 */
public final class ExecutableResolver {

    private ExecutableResolver() {
        // static-methods-only utility class
    }

    // -------------------------------------------------------------------------
    // Public result type
    // -------------------------------------------------------------------------

    /**
     * The result of a successful resolution.
     *
     * @param executable    the absolute {@link Path} of the resolved executable file
     * @param needsCmdWrapper {@code true} iff the file is a {@code .cmd} or {@code .bat} script on
     *                       Windows and therefore requires {@code cmd.exe /c} to be launched
     */
    public record Resolution(Path executable, boolean needsCmdWrapper) {}

    // -------------------------------------------------------------------------
    // Production entry point (reads real environment)
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code command} against the current process environment.
     *
     * <p>Reads:
     * <ul>
     *   <li>{@code os.name} system property — Windows iff it starts with "Windows" (case-insensitive)</li>
     *   <li>{@code PATH} environment variable, split on {@link File#pathSeparator}</li>
     *   <li>{@code PATHEXT} environment variable (Windows only), split on {@code ';'}; defaults to
     *       {@code .COM;.EXE;.BAT;.CMD} if absent or blank</li>
     * </ul>
     *
     * @param command the bare command name to resolve (e.g. {@code codex})
     * @return an {@link Optional} containing the {@link Resolution}, or empty if not found
     */
    public static Optional<Resolution> resolve(String command) {
        String osName = System.getProperty("os.name", "");
        boolean windows = osName.toLowerCase(Locale.ROOT).startsWith("windows");

        String pathEnv = System.getenv("PATH");
        List<Path> pathDirs;
        if (pathEnv == null || pathEnv.isBlank()) {
            pathDirs = List.of();
        } else {
            pathDirs = Arrays.stream(pathEnv.split(File.pathSeparator))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Path::of)
                    .toList();
        }

        List<String> pathExts;
        if (windows) {
            String pathExtEnv = System.getenv("PATHEXT");
            String effective = (pathExtEnv == null || pathExtEnv.isBlank())
                    ? ".COM;.EXE;.BAT;.CMD"
                    : pathExtEnv;
            pathExts = Arrays.stream(effective.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else {
            pathExts = List.of();
        }

        return resolve(command, windows, pathDirs, pathExts);
    }

    // -------------------------------------------------------------------------
    // Package-private testable core (pure function — no System/env access)
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code command} given explicit environment inputs. No calls to
     * {@code System.getenv}, {@code System.getProperty}, or any other global state.
     *
     * <p>Resolution rules:
     * <ol>
     *   <li>If {@code command} is blank, return empty.</li>
     *   <li>If {@code command} contains a path separator ({@code /} or {@code \}) or is an
     *       absolute path, treat it as a direct path candidate (skip {@code pathDirs} search):
     *       on Windows, try the path as-is and then with each {@code pathExt} appended (if the
     *       name has no recognised extension); on Unix, just the path as-is. Return the first
     *       candidate that {@link Files#isRegularFile exists as a regular file}.</li>
     *   <li>Otherwise, search each directory in {@code pathDirs} in order:
     *       <ul>
     *         <li><b>Unix:</b> candidate = {@code dir/command}; if it is a regular file, return it.</li>
     *         <li><b>Windows:</b> if {@code command} already ends (case-insensitively) with one of
     *             {@code pathExts}, try {@code dir/command} as-is; then try
     *             {@code dir/(command+ext)} for each {@code ext} in {@code pathExts}. Return the
     *             first existing regular file.</li>
     *       </ul>
     *   </li>
     *   <li>{@code needsCmdWrapper} = {@code windows} <em>and</em> the resolved file name ends
     *       (case-insensitively) with {@code .cmd} or {@code .bat}.</li>
     * </ol>
     *
     * @param command   the command name or path to resolve
     * @param windows   {@code true} if the target OS is Windows
     * @param pathDirs  ordered list of directories to search (mirrors {@code PATH})
     * @param pathExts  ordered list of recognised extensions (mirrors {@code PATHEXT}); used only
     *                  when {@code windows} is {@code true}
     * @return an {@link Optional} containing the {@link Resolution}, or empty if not found
     */
    static Optional<Resolution> resolve(
            String command,
            boolean windows,
            List<Path> pathDirs,
            List<String> pathExts) {

        if (command == null || command.isBlank()) {
            return Optional.empty();
        }

        // Determine whether the command string looks like a direct path reference.
        boolean isDirect = command.contains("/") || command.contains("\\")
                || Path.of(command).isAbsolute();

        if (isDirect) {
            Path base = Path.of(command);
            Optional<Path> found = resolveDirectPath(base, windows, pathExts);
            return found.map(p -> new Resolution(p, needsCmdWrapper(p, windows)));
        }

        // Search PATH directories.
        for (Path dir : pathDirs) {
            Optional<Path> found = resolveInDir(dir, command, windows, pathExts);
            if (found.isPresent()) {
                Path p = found.get();
                return Optional.of(new Resolution(p, needsCmdWrapper(p, windows)));
            }
        }

        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Tries to resolve a direct (path-containing) command candidate.
     *
     * <p>On Unix: just checks the path as-is.
     * On Windows: checks as-is, then with each {@code pathExt} appended (only if the name does not
     * already carry a recognised extension).
     */
    private static Optional<Path> resolveDirectPath(
            Path base, boolean windows, List<String> pathExts) {

        if (Files.isRegularFile(base)) {
            return Optional.of(base);
        }

        if (windows) {
            String name = base.getFileName() == null ? "" : base.getFileName().toString();
            if (!hasRecognisedExt(name, pathExts)) {
                for (String ext : pathExts) {
                    Path candidate = base.resolveSibling(name + ext);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Tries to find {@code command} inside a single {@code dir}.
     *
     * <p>Unix: one attempt — {@code dir/command}.
     * Windows: try as-is if {@code command} already carries a recognised extension, then try with
     * each extension in {@code pathExts}.
     */
    private static Optional<Path> resolveInDir(
            Path dir, String command, boolean windows, List<String> pathExts) {

        if (!windows) {
            Path candidate = dir.resolve(command);
            return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        // Windows: try as-is first when command already has a recognised extension.
        if (hasRecognisedExt(command, pathExts)) {
            Path candidate = dir.resolve(command);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }

        // Then try each PATHEXT extension in order.
        for (String ext : pathExts) {
            Path candidate = dir.resolve(command + ext);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    /**
     * Returns {@code true} if {@code name} ends (case-insensitively) with one of {@code pathExts}.
     */
    private static boolean hasRecognisedExt(String name, List<String> pathExts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : pathExts) {
            if (lower.endsWith(ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} iff {@code windows} is {@code true} and the file name of {@code path}
     * ends (case-insensitively) with {@code .cmd} or {@code .bat}.
     */
    private static boolean needsCmdWrapper(Path path, boolean windows) {
        if (!windows) {
            return false;
        }
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }
}
