package com.smartfileorganizer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Rejects roots, the home folder itself, and protected system trees. */
public final class SafetyChecker {
    private final Path home;
    private final Set<Path> protectedFolders;

    /** Creates the platform's default safety policy. */
    public SafetyChecker() {
        this(Path.of(System.getProperty("user.home")), systemFolders());
    }

    SafetyChecker(Path home, Set<Path> protectedFolders) {
        this.home = home.toAbsolutePath().normalize();
        this.protectedFolders = Set.copyOf(protectedFolders);
    }

    /**
     * Validates a selected folder, resolving aliases before returning it.
     * @param folder selected folder
     * @return canonical, readable directory
     * @throws IOException if the directory is unsafe or unavailable
     */
    public Path validate(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath().normalize();
        rejectDangerous(absolute);
        Path real = absolute.toRealPath();
        rejectDangerous(real);
        if (!Files.isDirectory(real) || !Files.isReadable(real)) {
            throw new IOException("Choose an existing, readable folder.");
        }
        return real;
    }

    private void rejectDangerous(Path folder) throws IOException {
        Path realHome = Files.exists(home) ? home.toRealPath() : home;
        if (folder.getParent() == null || folder.equals(home) || folder.equals(realHome)) {
            throw new IOException("Choose a folder inside your home, such as Downloads; roots are protected.");
        }
        for (Path protectedFolder : protectedFolders) {
            Path absolute = protectedFolder.toAbsolutePath().normalize();
            Path real = Files.exists(absolute) ? absolute.toRealPath() : absolute;
            if (folder.startsWith(absolute) || folder.startsWith(real)) {
                throw new IOException("System and application folders are protected.");
            }
        }
    }

    private static Set<Path> systemFolders() {
        var folders = new HashSet<Path>();
        if (System.getProperty("os.name").startsWith("Windows")) {
            folders.add(Path.of("C:\\Windows"));
            folders.add(Path.of("C:\\Program Files"));
            folders.add(Path.of("C:\\Program Files (x86)"));
            for (String key : Set.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
                String value = System.getenv(key);
                if (value != null && !value.isBlank()) {
                    folders.add(Path.of(value));
                }
            }
        }
        return folders;
    }
}
