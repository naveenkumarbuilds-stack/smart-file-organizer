package com.smartfileorganizer;

import com.formdev.flatlaf.FlatLightLaf;
import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.ui.MainFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.*;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;

/** Entry point for Smart File Organizer. */
public final class Main {
    private Main() {
    }

    /**
     * Starts logging and creates the desktop interface on Swing's event thread.
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        UndoLogStore store = UndoLogStore.forUser();
        String loggingWarning = "";
        try {
            Files.createDirectories(store.path().getParent());
            var handler = new FileHandler(store.path().resolveSibling("organizer-%g.log").toString(),
                    1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("com.smartfileorganizer").addHandler(handler);
        } catch (IOException | SecurityException exception) {
            Logger.getLogger(Main.class.getName()).log(Level.WARNING, "Cannot open application log", exception);
            loggingWarning = "The application log could not be opened. Check app-data permissions and disk space.";
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, "Unexpected application error", exception);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "An unexpected error occurred. Refresh the preview before continuing. Details are in the application log.",
                    "Application error", JOptionPane.ERROR_MESSAGE));
        });
        FlatLightLaf.setup();
        String notice = loggingWarning;
        SwingUtilities.invokeLater(() -> {
            var window = new MainFrame(new OrganizerService(store));
            window.setVisible(true);
            if (!notice.isEmpty()) JOptionPane.showMessageDialog(window, notice, "Logging unavailable",
                    JOptionPane.WARNING_MESSAGE);
        });
    }
}
