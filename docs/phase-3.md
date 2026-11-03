# Phase 3: Safe scanning and preview planning

## 1. Goal
Collect eligible files directly inside a safe selected folder. Build an immutable preview without moving files or creating category folders.

## 2. Files
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\SafetyChecker.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FolderScanner.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\MovePlanner.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\core\SafetyCheckerTest.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\core\FolderScannerTest.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\core\MovePlannerTest.java

## 3. Complete code

### src/main/java/com/smartfileorganizer/core/SafetyChecker.java

```java
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
```

### src/main/java/com/smartfileorganizer/core/FolderScanner.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects eligible direct-child files and destination names for a preview. */
public final class FolderScanner {
    private final SafetyChecker safety = new SafetyChecker();
    private static final Set<String> TEMP_EXTENSIONS = Set.of("crdownload", "part", "tmp");

    /** Creates a scanner with the default safety policy. */
    public FolderScanner() {
    }

    /**
     * Immutable scan snapshot. Skipped entries include reasons for the UI.
     * @param folder canonical source directory
     * @param files eligible sources
     * @param occupied existing names in category folders
     * @param skipped reasons for excluded entries
     */
    public record ScanResult(Path folder, List<Path> files, Set<Path> occupied, List<String> skipped) {
        /** Defensively copies the snapshot collections. */
        public ScanResult {
            files = List.copyOf(files);
            occupied = Set.copyOf(occupied);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * Scans without moving files or creating category folders.
     * Category directory names are read only to reserve occupied destinations;
     * their contents never become source files and are not scanned recursively.
     * @param folder selected folder
     * @param undoLog exact app undo-log path to exclude
     * @return immutable snapshot for the pure planner
     * @throws IOException if the source or a destination cannot be inspected safely
     */
    public ScanResult scan(Path folder, Path undoLog) throws IOException {
        Path root = safety.validate(folder);
        Path excluded = undoLog.toAbsolutePath().normalize();
        Path realExcluded = Files.exists(excluded) ? excluded.toRealPath() : excluded;
        var files = new ArrayList<Path>();
        var skipped = new ArrayList<String>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                try {
                    String reason = exclusionReason(entry, excluded, realExcluded);
                    if (reason == null) {
                        files.add(entry);
                    } else {
                        skipped.add(entry.getFileName() + ": " + reason);
                    }
                } catch (IOException | SecurityException | OverlappingFileLockException exception) {
                    skipped.add(entry.getFileName() + ": unavailable or in use");
                }
            }
        }
        var occupied = new HashSet<Path>();
        for (FileCategory category : FileCategory.values()) {
            Path destination = root.resolve(category.folderName());
            if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                    || !destination.toRealPath().equals(destination)) {
                throw new IOException("The category folder " + category.folderName() + " is blocked or linked.");
            }
            try (var entries = Files.newDirectoryStream(destination)) {
                entries.forEach(occupied::add);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        skipped.sort(String::compareTo);
        return new ScanResult(root, files, occupied, skipped);
    }

    private String exclusionReason(Path file, Path excluded, Path realExcluded) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().equals(file)) {
            return "not a direct regular file";
        }
        if (file.equals(excluded) || file.toRealPath().equals(realExcluded)
                || (Files.exists(excluded) && Files.isSameFile(file, excluded))) {
            return "app undo log";
        }
        String name = file.getFileName().toString();
        if (name.startsWith(".") || Files.isHidden(file)) {
            return "hidden file";
        }
        var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            DosFileAttributes attributes = dos.readAttributes();
            if (attributes.isSystem() || attributes.isHidden() || attributes.isReadOnly()) {
                return "protected file";
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && TEMP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            return "temporary download";
        }
        // Best-effort detection only: other programs may open the file after this check.
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            return lock == null ? "file in use" : null;
        }
    }
}
```

### src/main/java/com/smartfileorganizer/core/MovePlanner.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.PlannedMove;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds immutable previews from snapshots; performs no file-system operations. */
public final class MovePlanner {
    private final CategoryClassifier classifier = new CategoryClassifier();

    /** Creates a planner using the default classifier. */
    public MovePlanner() {
    }

    /**
     * Plans direct-child sources, reserving existing and planned destinations.
     * Case-insensitive reservation conservatively protects Windows filenames.
     * @param folder validated source folder
     * @param files eligible source paths from the scanner
     * @param occupied existing destination paths from the scanner
     * @return deterministic immutable moves
     */
    public List<PlannedMove> plan(Path folder, List<Path> files, Set<Path> occupied) {
        Path root = folder.toAbsolutePath().normalize();
        var reserved = new HashSet<String>();
        occupied.forEach(path -> reserved.add(key(path)));
        var seen = new HashSet<String>();
        var moves = new ArrayList<PlannedMove>();
        for (Path file : files.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            Path source = file.toAbsolutePath().normalize();
            if (!root.equals(source.getParent()) || !seen.add(key(source))) {
                throw new IllegalArgumentException("Sources must be unique direct children of the selected folder.");
            }
            var category = classifier.classify(source);
            String name = source.getFileName().toString();
            int dot = name.lastIndexOf('.');
            boolean hasExtension = dot > 0 && dot < name.length() - 1;
            String stem = hasExtension ? name.substring(0, dot) : name;
            String extension = hasExtension ? name.substring(dot) : "";
            Path targetFolder = root.resolve(category.folderName());
            Path target = targetFolder.resolve(name);
            int suffix = 1;
            while (!reserved.add(key(target))) {
                target = targetFolder.resolve(stem + " (" + suffix++ + ")" + extension);
            }
            moves.add(new PlannedMove(source, target, category));
        }
        return List.copyOf(moves);
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
```

### src/test/java/com/smartfileorganizer/core/SafetyCheckerTest.java

```java
package com.smartfileorganizer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SafetyCheckerTest {
    @TempDir Path temp;

    @Test
    void refusesRootAndActualHome() {
        var checker = new SafetyChecker();
        assertThrows(IOException.class, () -> checker.validate(temp.toAbsolutePath().getRoot()));
        assertThrows(IOException.class, () -> checker.validate(Path.of(System.getProperty("user.home"))));
    }

    @Test
    void refusesProtectedTreeAndNormalizedPathsButAllowsHomeChildren() throws IOException {
        Path home = Files.createDirectory(temp.resolve("home"));
        Path downloads = Files.createDirectory(home.resolve("Downloads"));
        Path system = Files.createDirectory(temp.resolve("system"));
        Path child = Files.createDirectory(system.resolve("child"));
        var checker = new SafetyChecker(home, Set.of(system));
        assertThrows(IOException.class, () -> checker.validate(home));
        assertThrows(IOException.class, () -> checker.validate(system));
        assertThrows(IOException.class, () -> checker.validate(child));
        assertThrows(IOException.class, () -> checker.validate(downloads.resolve("..")));
        assertEquals(downloads.toRealPath(), checker.validate(downloads));
    }

    @Test
    void rejectsMissingFoldersAndRegularFiles() throws IOException {
        var checker = new SafetyChecker();
        Path file = Files.writeString(temp.resolve("file.txt"), "data");
        assertThrows(IOException.class, () -> checker.validate(file));
        assertThrows(IOException.class, () -> checker.validate(temp.resolve("missing")));
    }

    @Test
    void refusesWindowsSystemFoldersWithoutChangingThem() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            var checker = new SafetyChecker();
            for (String name : Set.of("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)")) {
                assertThrows(IOException.class, () -> checker.validate(Path.of(name)));
            }
        }
    }
}
```

### src/test/java/com/smartfileorganizer/core/FolderScannerTest.java

```java
package com.smartfileorganizer.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FolderScannerTest {
    @TempDir Path temp;
    private final FolderScanner scanner = new FolderScanner();

    private FolderScanner.ScanResult scan() throws IOException {
        return scanner.scan(temp, temp.resolve("undo.json"));
    }

    @Test
    void scansOnlyEligibleDirectFilesAndExcludesUndoAndDownloads() throws IOException {
        Path photo = Files.writeString(temp.resolve("photo.jpg"), "photo");
        for (String name : List.of("undo.json", ".hidden", "download.CRDOWNLOAD", "video.part", "work.tmp")) {
            Files.writeString(temp.resolve(name), "unchanged");
        }
        Path nested = Files.createDirectory(temp.resolve("nested"));
        Files.writeString(nested.resolve("keep.txt"), "nested");
        var result = scan();
        assertEquals(List.of(photo.toRealPath()), result.files());
        assertEquals(6, result.skipped().size());
        assertEquals("photo", Files.readString(photo));
        assertEquals("nested", Files.readString(nested.resolve("keep.txt")));
        assertFalse(Files.exists(temp.resolve("Images")));
        assertThrows(UnsupportedOperationException.class, () -> result.files().clear());
    }

    @Test
    void skipsLockedFileAndContinues() throws IOException {
        Path locked = Files.writeString(temp.resolve("locked.txt"), "locked");
        Path free = Files.writeString(temp.resolve("free.txt"), "free");
        try (var channel = FileChannel.open(locked, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var result = scan();
            assertEquals(List.of(free.toRealPath()), result.files());
            assertTrue(result.skipped().stream().anyMatch(message -> message.startsWith("locked.txt:")));
        }
        assertEquals(2, scan().files().size());
    }

    @Test
    void skipsWindowsHiddenSystemAndReadOnlyFiles() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        for (String flag : List.of("hidden", "system", "readonly")) {
            Path file = Files.writeString(temp.resolve(flag + ".txt"), "protected");
            var view = Files.getFileAttributeView(file, DosFileAttributeView.class);
            try {
                switch (flag) {
                    case "hidden" -> view.setHidden(true);
                    case "system" -> view.setSystem(true);
                    case "readonly" -> view.setReadOnly(true);
                    default -> throw new AssertionError(flag);
                }
                assertFalse(scan().files().contains(file.toRealPath()));
            } finally {
                view.setHidden(false);
                view.setSystem(false);
                view.setReadOnly(false);
            }
        }
    }

    @Test
    void snapshotsOccupiedNamesAndPlansWithoutMoving() throws IOException {
        Path images = Files.createDirectory(temp.resolve("Images"));
        Path existing = Files.writeString(images.resolve("photo.jpg"), "existing");
        Path source = Files.writeString(temp.resolve("photo.jpg"), "new");
        var result = scan();
        var plan = new MovePlanner().plan(result.folder(), result.files(), result.occupied());
        assertTrue(result.occupied().contains(existing.toRealPath()));
        assertEquals(images.toRealPath().resolve("photo (1).jpg"), plan.getFirst().destination());
        assertEquals("existing", Files.readString(existing));
        assertEquals("new", Files.readString(source));
        assertFalse(Files.exists(plan.getFirst().destination()));
    }

    @Test
    void refusesBlockedCategoryAndDeletedSourceFolder() throws IOException {
        Files.writeString(temp.resolve("Images"), "cannot be a directory");
        assertThrows(IOException.class, this::scan);
        Path removed = Files.createDirectory(temp.resolve("removed"));
        Files.delete(removed);
        assertThrows(IOException.class, () -> scanner.scan(removed, temp.resolve("undo.json")));
    }
}
```

### src/test/java/com/smartfileorganizer/core/MovePlannerTest.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.FileCategory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MovePlannerTest {
    @TempDir Path temp;
    private final MovePlanner planner = new MovePlanner();

    @Test
    void plansFromSnapshotsWithoutCreatingAnything() {
        Path root = temp.resolve("does-not-exist");
        Path source = root.resolve("photo.JPG");
        var plan = planner.plan(root, List.of(source), Set.of());
        assertEquals(source, plan.getFirst().source());
        assertEquals(root.resolve("Images/photo.JPG"), plan.getFirst().destination());
        assertEquals(FileCategory.IMAGES, plan.getFirst().category());
        assertFalse(Files.exists(root));
        assertThrows(UnsupportedOperationException.class, () -> plan.clear());
    }

    @Test
    void reservesExistingAndBatchNamesCaseInsensitively() {
        Path first = temp.resolve("photo (1).jpg");
        Path second = temp.resolve("photo.jpg");
        Set<Path> occupied = Set.of(temp.resolve("Images/PHOTO.JPG"));
        var plan = planner.plan(temp, List.of(second, first), occupied);
        assertEquals(temp.resolve("Images/photo (1).jpg"), plan.get(0).destination());
        assertEquals(temp.resolve("Images/photo (2).jpg"), plan.get(1).destination());
        assertEquals(plan, planner.plan(temp, List.of(first, second), occupied));
        assertEquals(1, occupied.size());
    }

    @Test
    void renamesExtensionlessAndMultiDotFiles() {
        var plan = planner.plan(temp, List.of(temp.resolve("README"), temp.resolve("backup.tar.gz")),
                Set.of(temp.resolve("Others/README"), temp.resolve("Archives/backup.tar.gz")));
        assertEquals(temp.resolve("Others/README (1)"), plan.get(0).destination());
        assertEquals(temp.resolve("Archives/backup.tar (1).gz"), plan.get(1).destination());
    }

    @Test
    void rejectsNestedOutsideAndDuplicateSources() {
        Path file = temp.resolve("file.txt");
        for (List<Path> sources : List.of(List.of(temp.resolve("nested/file.txt")),
                List.of(temp.getParent().resolve("outside.txt")), List.of(file, file))) {
            assertThrows(IllegalArgumentException.class, () -> planner.plan(temp, sources, Set.of()));
        }
        assertTrue(planner.plan(temp, List.of(), Set.of()).isEmpty());
    }
}
```

## 4. How to run and verify
With Maven installed, run from the project directory:
```powershell
mvn clean test
```
Expected on Windows: 77 tests, zero failures and errors, BUILD SUCCESS. The Windows-specific attribute test is skipped on other platforms.

Verified in this workspace: all 77 JUnit tests passed. Compiled with installed JDK 25 using --release 21 and run with the standalone JUnit 1.13.4 console runner. Maven and a JDK 21 runtime remain unverified.

## 5. How it works
SafetyChecker resolves paths and rejects drive roots, the home folder itself, and Windows system/application trees. Downloads and other home subfolders remain allowed.
FolderScanner excludes hidden/system/read-only/temp/locked files, links, directories, and the supplied undo-log path. Per-file inspection failures become skipped reasons. It reads names in existing category directories solely to reserve destinations; these never become sources. A blocked or linked category directory aborts the preview safely.
MovePlanner takes snapshots, performs no disk access, and returns a deterministic immutable list. Existing and planned names are reserved without case sensitivity; collisions use name (1).ext, name (2).ext, and so on. Files ending in multiple extensions use the final extension, such as backup.tar (1).gz.
Lock detection is best effort: Java locks depend on the platform and other programs may open or change files after scanning. Phase 4 must revalidate every planned operation and refuse changed destinations rather than silently executing a different preview.
Reference: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html
Reference: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html

## 6. Common errors
- Maven not recognized: install Maven and add its bin directory to PATH, then reopen the terminal.
- Release 21 not supported: configure the editor and JAVA_HOME to use JDK 21 or newer.
- Protected-folder error: choose a normal working folder rather than a drive root, home root, or system/application directory.
- Category blocked or linked: inspect the named category path. It must be a normal directory or absent; the app will not remove or replace it.
- A file is skipped as unavailable: close the program using it and scan again. Read-only files are conservatively skipped.
- The application still prints the setup message: expected until the interface is implemented in Phase 5.
