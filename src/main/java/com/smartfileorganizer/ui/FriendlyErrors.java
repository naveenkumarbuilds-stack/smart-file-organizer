package com.smartfileorganizer.ui;

import java.nio.file.*;
import java.util.Locale;

/** Converts operating-system errors into short, actionable messages. */
public final class FriendlyErrors {
    private FriendlyErrors() {
    }

    /** Returns a message suitable for an error dialog, with full details left in the log. */
    public static String message(Throwable error) {
        if (error instanceof AccessDeniedException || error instanceof SecurityException) {
            return "Access was denied. Check folder permissions and close applications using these files.";
        }
        if (error instanceof NoSuchFileException) {
            return "The file or folder is no longer available. Choose an existing folder and refresh the preview.";
        }
        if (error instanceof FileAlreadyExistsException) {
            return "A destination is now occupied. Refresh the preview to calculate a free name.";
        }
        if (error instanceof FileSystemException filesystem) {
            String reason = filesystem.getReason();
            String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (text.contains("space") || text.contains("disk full")) {
                return "The disk may be full. Free some space, then refresh or try Undo.";
            }
            if (text.contains("too long") || text.contains("filename or extension is too long")) {
                return "A file path is too long. Try a folder closer to the drive root or shorten the filename.";
            }
            return "A file operation could not finish. Check the drive connection, permissions, and files in use.";
        }
        if (error instanceof java.io.IOException && error.getMessage() != null) {
            return error.getMessage();
        }
        return "The operation could not finish. Refresh the preview or try Undo. Details are in the application log.";
    }
}
