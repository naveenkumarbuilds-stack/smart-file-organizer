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
