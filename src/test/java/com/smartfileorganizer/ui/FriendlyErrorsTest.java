package com.smartfileorganizer.ui;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FriendlyErrorsTest {
    @Test void translatesOperatingSystemErrorsIntoActions() {
        assertTrue(FriendlyErrors.message(new AccessDeniedException("file")).contains("permissions"));
        assertTrue(FriendlyErrors.message(new NoSuchFileException("file")).contains("no longer available"));
        assertTrue(FriendlyErrors.message(new FileAlreadyExistsException("file")).contains("Refresh"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file", null, "No space left on device")).contains("full"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file", null, "File name too long")).contains("too long"));
        assertTrue(FriendlyErrors.message(new FileSystemException("file")).contains("drive connection"));
        assertFalse(FriendlyErrors.message(new IllegalStateException("internal stack details")).contains("internal stack"));
    }
}
