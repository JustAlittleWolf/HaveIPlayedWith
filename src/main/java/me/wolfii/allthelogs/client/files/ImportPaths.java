package me.wolfii.allthelogs.client.files;

import me.wolfii.allthelogs.data.ImportOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Path helpers for the import form: archive detection, dropped-file resolution, and a sensible
 * starting folder for the system picker.
 */
public final class ImportPaths {
    private ImportPaths() {
    }

    public static boolean isArchive(Path path) {
        Path name = path == null ? null : path.getFileName();
        if (name == null) return false;
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".tgz")
            || lower.endsWith(".tar.gz") || lower.endsWith(".tar");
    }

    public static boolean isLogFile(Path path) {
        Path name = path == null ? null : path.getFileName();
        if (name == null) return false;
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".log") || lower.endsWith(".log.gz");
    }

    /**
     * Chooses an import path from files dropped onto the Minecraft window. Directories win, then
     * archives, then the parent of a dropped log file.
     */
    public static Optional<Path> fromDropped(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return Optional.empty();
        Path firstArchive = null;
        Path firstLog = null;
        Path firstFile = null;
        for (Path path : paths) {
            if (path == null) continue;
            Path absolute = path.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                return Optional.of(absolute);
            }
            if (firstArchive == null && isArchive(absolute)) {
                firstArchive = absolute;
            }
            if (firstLog == null && isLogFile(absolute)) {
                firstLog = absolute;
            }
            if (firstFile == null && Files.isRegularFile(absolute)) {
                firstFile = absolute;
            }
        }
        if (firstArchive != null) return Optional.of(firstArchive);
        if (firstLog != null && firstLog.getParent() != null) {
            return Optional.of(firstLog.getParent());
        }
        if (firstFile != null && firstFile.getParent() != null) {
            return Optional.of(firstFile.getParent());
        }
        return Optional.empty();
    }

    public static Path startDirectory(Path initial) {
        if (initial != null && Files.isDirectory(initial)) {
            return initial.toAbsolutePath().normalize();
        }
        if (initial != null && initial.getParent() != null) {
            return initial.toAbsolutePath().normalize().getParent();
        }
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    /**
     * Advanced options used when the import form opens, when Custom path is chosen, and when
     * From Folder / From Archive is used while Advanced is collapsed.
     */
    public static ImportOptions customPathDefaults() {
        return ImportOptions.defaults().withSkipAlreadyImported(true);
    }

    /**
     * Folder and archive pickers leave the current advanced options alone while Advanced is
     * expanded, so the user can keep edits they are looking at. Collapsed, they reset to
     * {@link #customPathDefaults()} so a previous launcher preset is not applied silently.
     */
    public static ImportOptions afterFolderOrArchivePick(boolean advancedExpanded, ImportOptions current) {
        return advancedExpanded ? current : customPathDefaults();
    }

    /**
     * Import knobs for a folder chosen in the import form. A Minecraft instance directory (one that contains
     * a {@code logs} folder) is walked for {@code **&#47;logs&#47;**} without opening resource-pack zips. Other
     * folders keep the recursive nested-archive defaults.
     */
    public static ImportOptions optionsForFolder(Path folder) {
        if (folder != null && Files.isDirectory(folder.resolve("logs"))) {
            return ImportOptions.currentGameDirectory();
        }
        return customPathDefaults();
    }
}
