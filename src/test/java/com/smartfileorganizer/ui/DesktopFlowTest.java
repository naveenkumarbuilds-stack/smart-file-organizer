package com.smartfileorganizer.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.storage.UndoLogStore;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DesktopFlowTest {
    @TempDir Path temp;

    @Test void chooseSortExcludeOrganizeAndUndoThroughWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path source = Files.createDirectory(temp.resolve("Downloads"));
        Files.writeString(source.resolve("a.txt"), "alpha");
        Files.writeString(source.resolve("b.jpg"), "beta");
        var service = new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json")));
        MainFrame frame = onEdt(() -> {
            FlatLightLaf.setup();
            var window = new MainFrame(service);
            window.setVisible(true);
            return window;
        });
        try {
            await(() -> button(frame, "Choose folder").isEnabled());
            assertFalse(onEdt(() -> button(frame, "Organize").isEnabled()));
            assertFalse(onEdt(() -> button(frame, "Undo last organization").isEnabled()));
            click(frame, "Choose folder");
            await(() -> visible(JFileChooser.class) != null);
            onEdt(() -> {
                JFileChooser picker = visible(JFileChooser.class);
                picker.setSelectedFile(source.toFile());
                picker.approveSelection();
                return null;
            });
            await(() -> button(frame, "Organize").isEnabled());
            JTable table = onEdt(() -> find(frame, JTable.class));
            assertEquals(2, onEdt(table::getRowCount));
            onEdt(() -> {
                table.getRowSorter().toggleSortOrder(1);
                table.getRowSorter().toggleSortOrder(1);
                assertEquals("b.jpg", table.getValueAt(0, 1));
                table.setValueAt(false, 0, 0);
                var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                frame.paint(image.getGraphics());
                Files.createDirectories(Path.of("target"));
                ImageIO.write(image, "png", Path.of("target/phase5-preview.png").toFile());
                return null;
            });
            click(frame, "Organize");
            confirm();
            await(() -> visible(JOptionPane.class) != null);
            assertEquals("alpha", Files.readString(source.resolve("Documents/a.txt")));
            assertEquals("beta", Files.readString(source.resolve("b.jpg")));
            assertFalse(Files.exists(source.resolve("a.txt")));
            dismiss();
            await(() -> button(frame, "Undo last organization").isEnabled()
                    && button(frame, "Choose folder").isEnabled());
            click(frame, "Undo last organization");
            confirm();
            await(() -> visible(JOptionPane.class) != null);
            assertEquals("alpha", Files.readString(source.resolve("a.txt")));
            dismiss();
            await(() -> button(frame, "Choose folder").isEnabled());
            assertFalse(onEdt(() -> button(frame, "Undo last organization").isEnabled()));
            onEdt(() -> { frame.dispose(); return null; });
            MainFrame reopened = onEdt(() -> {
                var window = new MainFrame(new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json"))));
                window.setVisible(true);
                return window;
            });
            await(() -> button(reopened, "Organize").isEnabled());
            assertEquals(2, onEdt(() -> find(reopened, JTable.class).getRowCount()));
        } finally {
            onEdt(() -> {
                for (Window window : Window.getWindows()) window.dispose();
                return null;
            });
        }
    }

    @Test void progressIncludesEverySelectedAndUndoneFile() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");
        var service = new OrganizerService(new UndoLogStore(temp.resolve("state/undo.json")));
        var plan = service.preview(source);
        var progress = new ArrayList<Integer>();
        assertEquals(2, service.organize(plan.folder(), plan.moves(), progress::add).moved());
        assertEquals(java.util.List.of(50, 100), progress);
        progress.clear();
        assertEquals(2, service.undo(progress::add).moved());
        assertEquals(java.util.List.of(50, 100), progress);
    }

    private static void confirm() throws Exception {
        await(() -> visible(JOptionPane.class) != null);
        dismiss();
    }

    private static void dismiss() throws Exception {
        onEdt(() -> { visible(JOptionPane.class).setValue(JOptionPane.OK_OPTION); return null; });
        // Flush events after the modal event loop exits.
        onEdt(() -> null);
    }

    private static void click(Container parent, String label) {
        SwingUtilities.invokeLater(() -> button(parent, label).doClick());
    }

    private static JButton button(Container parent, String label) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton button && label.equals(button.getText())) return button;
            if (component instanceof Container container) {
                JButton found = button(container, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T visible(Class<T> type) {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                T found = find(window, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!onEdt(condition)) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for the desktop interface.");
            Thread.sleep(25);
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.call()); }
            catch (Exception exception) { failure.set(exception); }
        });
        if (failure.get() != null) throw failure.get();
        return value.get();
    }
}
