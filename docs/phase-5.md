# Phase 5 — Desktop interface

## 1. Goal
Choose a folder, inspect and select proposed moves, organize files, and undo the saved batch in one resizable window. Run scans and file operations in SwingWorker tasks so the interface stays responsive.

## 2. Files
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\Main.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\ui\MainFrame.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\ui\PreviewTableModel.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\OrganizerService.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\FileMover.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\main\java\com\smartfileorganizer\core\UndoManager.java
- C:\Users\navee\OneDrive\Desktop\opensorceproject\src\test\java\com\smartfileorganizer\ui\DesktopFlowTest.java

## 3. Complete code

### src/main/java/com/smartfileorganizer/Main.java

```java
package com.smartfileorganizer;

import com.formdev.flatlaf.FlatLightLaf;
import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.ui.MainFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.*;
import javax.swing.SwingUtilities;

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
        try {
            Files.createDirectories(store.path().getParent());
            var handler = new FileHandler(store.path().resolveSibling("organizer-%g.log").toString(),
                    1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("com.smartfileorganizer").addHandler(handler);
        } catch (IOException | SecurityException exception) {
            Logger.getLogger(Main.class.getName()).log(Level.WARNING, "Cannot open application log", exception);
        }
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> new MainFrame(new OrganizerService(store)).setVisible(true));
    }
}
```

### src/main/java/com/smartfileorganizer/ui/MainFrame.java

```java
package com.smartfileorganizer.ui;

import com.smartfileorganizer.core.OrganizerService;
import com.smartfileorganizer.model.OrganizeResult;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.*;
import javax.swing.*;

/** Single-window organizer with all service work performed by background workers. */
public final class MainFrame extends JFrame {
    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());
    private final OrganizerService service;
    private final PreviewTableModel model = new PreviewTableModel();
    private final JTable table = new JTable(model);
    private final JTextField folder = new JTextField("Choose a folder to begin");
    private final JButton choose = new JButton("Choose folder");
    private final JButton refresh = new JButton("Refresh preview");
    private final JButton organize = new JButton("Organize");
    private final JButton undo = new JButton("Undo last organization");
    private final JButton details = new JButton("Skipped files");
    private final JLabel status = new JLabel("Checking saved undo history...");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private OrganizerService.Preview preview;
    private Path chosenFolder;
    private boolean busy;
    private boolean undoAvailable;

    /** Creates a window on the Swing event thread using the supplied service. */
    public MainFrame(OrganizerService service) {
        super("Smart File Organizer");
        this.service = service;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(800, 480));
        setSize(1040, 640);
        setLocationRelativeTo(null);

        folder.setEditable(false);
        folder.setToolTipText("The folder whose direct files will be organized");
        var selector = new JPanel(new BorderLayout(12, 0));
        selector.add(new JLabel("Folder"), BorderLayout.WEST);
        selector.add(folder, BorderLayout.CENTER);
        var folderButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        folderButtons.add(choose);
        folderButtons.add(refresh);
        selector.add(folderButtons, BorderLayout.EAST);

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(72);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(430);
        table.putClientProperty("terminateEditOnFocusLost", true);

        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(180, 24));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(organize);
        actions.add(undo);
        actions.add(details);
        var bottom = new JPanel(new BorderLayout(12, 12));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.EAST);

        var content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.add(selector, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        choose.addActionListener(event -> chooseFolder());
        refresh.addActionListener(event -> scan());
        organize.addActionListener(event -> organize());
        undo.addActionListener(event -> undo());
        details.addActionListener(event -> showSkipped());
        model.addTableModelListener(event -> updateButtons());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) {
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Please wait for the current operation to finish.", "Work in progress",
                            JOptionPane.INFORMATION_MESSAGE);
                } else {
                    dispose();
                }
            }
        });
        runTask("Checking saved undo history...", true, report -> null,
                ignored -> status.setText("Choose a folder to preview its files."));
    }

    private void chooseFolder() {
        var picker = chosenFolder == null ? new JFileChooser() : new JFileChooser(chosenFolder.toString());
        picker.setDialogTitle("Choose a folder to organize");
        picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        picker.setAcceptAllFileFilterUsed(false);
        if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            chosenFolder = picker.getSelectedFile().toPath();
            folder.setText(chosenFolder.toString());
            scan();
        }
    }

    private void scan() {
        if (chosenFolder == null) return;
        preview = null;
        model.setMoves(java.util.List.of());
        Path selected = chosenFolder;
        runTask("Scanning folder...", true, report -> service.preview(selected), result -> {
            preview = result;
            chosenFolder = result.folder();
            folder.setText(chosenFolder.toString());
            model.setMoves(result.moves());
            selectionStatus();
        });
    }

    private void organize() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        var selected = model.selectedMoves();
        if (preview == null || selected.isEmpty()) return;
        long folders = selected.stream().map(move -> move.destination().getParent()).distinct().count();
        String question = "Move " + selected.size() + " files into " + folders + " folders?";
        if (undoAvailable) question += "\nThis will replace the saved undo history with this organization.";
        if (JOptionPane.showConfirmDialog(this, question, "Confirm organization",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        Path source = preview.folder();
        clearPreview();
        runTask("Organizing files...", false, report -> service.organize(source, selected, report),
                result -> finish("Organization complete", result, false));
    }

    private void undo() {
        if (JOptionPane.showConfirmDialog(this, "Restore files from the last saved organization?",
                "Confirm undo", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        clearPreview();
        runTask("Restoring files...", false, service::undo, result -> finish("Undo complete", result, true));
    }

    private void clearPreview() {
        preview = null;
        model.setMoves(java.util.List.of());
    }

    private void finish(String title, OrganizeResult result, boolean restoring) {
        String message = (restoring ? "Restored: " : "Moved: ") + result.moved()
                + "\nSkipped: " + result.skipped() + "\nFailed: " + result.failed();
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
        status.setText(title);
        if (chosenFolder != null) scan();
    }

    private void showSkipped() {
        if (preview == null || preview.skipped().isEmpty()) return;
        var text = new JTextArea(String.join("\n", preview.skipped()), 16, 65);
        text.setEditable(false);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Files excluded from preview",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void selectionStatus() {
        if (preview != null && !busy) {
            status.setText(model.selectedMoves().size() + " of " + model.getRowCount()
                    + " files selected; " + preview.skipped().size() + " entries excluded.");
        }
    }

    private void updateButtons() {
        choose.setEnabled(!busy);
        refresh.setEnabled(!busy && chosenFolder != null);
        organize.setEnabled(!busy && preview != null && !model.selectedMoves().isEmpty());
        undo.setEnabled(!busy && undoAvailable);
        details.setEnabled(!busy && preview != null && !preview.skipped().isEmpty());
        selectionStatus();
    }

    @FunctionalInterface
    private interface Task<T> {
        T run(IntConsumer report) throws Exception;
    }

    private <T> void runTask(String message, boolean indeterminate, Task<T> task, Consumer<T> completed) {
        busy = true;
        model.setEditable(false);
        status.setText(message);
        progress.setValue(0);
        progress.setIndeterminate(indeterminate);
        updateButtons();
        var worker = new SwingWorker<T, Void>() {
            private boolean savedUndoAvailable;
            private Exception historyFailure;

            @Override protected T doInBackground() throws Exception {
                try {
                    return task.run(this::setProgress);
                } finally {
                    try {
                        savedUndoAvailable = service.hasUndo();
                    } catch (Exception exception) {
                        historyFailure = exception;
                        LOG.log(Level.WARNING, "Cannot refresh saved undo history", exception);
                    }
                }
            }

            @Override protected void done() {
                busy = false;
                undoAvailable = savedUndoAvailable;
                progress.setIndeterminate(false);
                model.setEditable(true);
                try {
                    T result = get();
                    progress.setValue(100);
                    completed.accept(result);
                    if (historyFailure != null) showError(historyFailure);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError(exception);
                } catch (ExecutionException exception) {
                    showError(exception.getCause());
                } finally {
                    updateButtons();
                }
            }
        };
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) progress.setValue((Integer) event.getNewValue());
        });
        worker.execute();
    }

    private void showError(Throwable exception) {
        LOG.log(Level.WARNING, "Organizer operation failed", exception);
        status.setText("Operation could not finish. Refresh the preview or try Undo.");
        String message = exception instanceof java.io.IOException && exception.getMessage() != null
                ? exception.getMessage() : "The operation could not finish. Please refresh and try again.";
        JOptionPane.showMessageDialog(this, message, "Unable to complete operation", JOptionPane.ERROR_MESSAGE);
    }
}
```

### src/main/java/com/smartfileorganizer/ui/PreviewTableModel.java

```java
package com.smartfileorganizer.ui;

import com.smartfileorganizer.model.PlannedMove;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Editable inclusion checkboxes backed by immutable preview rows. */
public final class PreviewTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"Include", "File", "Category", "Destination"};
    private List<PlannedMove> moves = List.of();
    private boolean[] included = new boolean[0];
    private boolean editable = true;

    /** Creates an empty preview model. */
    public PreviewTableModel() {
    }

    /** Replaces the preview and includes every row initially. */
    public void setMoves(List<PlannedMove> moves) {
        this.moves = List.copyOf(moves);
        included = new boolean[moves.size()];
        java.util.Arrays.fill(included, true);
        fireTableDataChanged();
    }

    /** Enables or disables checkbox changes during background work. */
    public void setEditable(boolean editable) {
        this.editable = editable;
        fireTableDataChanged();
    }

    /** Returns selected rows in model order, independent of table sorting. */
    public List<PlannedMove> selectedMoves() {
        var selected = new ArrayList<PlannedMove>();
        for (int row = 0; row < moves.size(); row++) {
            if (included[row]) selected.add(moves.get(row));
        }
        return List.copyOf(selected);
    }

    /** Returns the preview row count. */
    @Override public int getRowCount() { return moves.size(); }

    /** Returns the number of columns. */
    @Override public int getColumnCount() { return COLUMNS.length; }

    /** Returns a column's display label. */
    @Override public String getColumnName(int column) { return COLUMNS[column]; }

    /** Returns Boolean for checkboxes and String for text columns. */
    @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }

    /** Allows edits only in the inclusion column while idle. */
    @Override public boolean isCellEditable(int row, int column) { return editable && column == 0; }

    /** Returns the display value without accessing disk. */
    @Override public Object getValueAt(int row, int column) {
        PlannedMove move = moves.get(row);
        return switch (column) {
            case 0 -> included[row];
            case 1 -> move.source().getFileName().toString();
            case 2 -> move.category().folderName();
            case 3 -> move.source().getParent().relativize(move.destination()).toString();
            default -> throw new IndexOutOfBoundsException(column);
        };
    }

    /** Updates a checkbox and notifies listeners that the selection changed. */
    @Override public void setValueAt(Object value, int row, int column) {
        if (isCellEditable(row, column) && value instanceof Boolean selected) {
            included[row] = selected;
            fireTableCellUpdated(row, column);
        }
    }
}
```

### src/main/java/com/smartfileorganizer/core/OrganizerService.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.PlannedMove;
import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/** Small application service used by the desktop interface. All calls may access disk. */
public final class OrganizerService {
    private final UndoLogStore store;

    /** Uses the supplied journal, allowing isolated application tests. */
    public OrganizerService(UndoLogStore store) {
        this.store = store;
    }

    /** Immutable preview containing the canonical folder and scan exclusions. */
    public record Preview(Path folder, List<PlannedMove> moves, List<String> skipped) {
        /** Copies the collections to keep the preview stable. */
        public Preview {
            moves = List.copyOf(moves);
            skipped = List.copyOf(skipped);
        }
    }

    /** Scans a folder and plans its eligible moves. */
    public Preview preview(Path folder) throws IOException {
        var scan = new FolderScanner().scan(folder, store.path());
        var moves = new MovePlanner().plan(scan.folder(), scan.files(), scan.occupied());
        return new Preview(scan.folder(), moves, scan.skipped());
    }

    /** Reports whether persisted undo entries remain. */
    public boolean hasUndo() throws IOException {
        return new UndoManager(store).hasUndo();
    }

    /** Executes the selected preview rows and reports progress. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        return new FileMover(store).organize(folder, moves, progress);
    }

    /** Undoes the saved batch and reports progress. */
    public OrganizeResult undo(IntConsumer progress) throws IOException {
        return new UndoManager(store).undo(progress);
    }
}
```

### src/main/java/com/smartfileorganizer/core/FileMover.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.*;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.*;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Executes selected preview rows and journals each move before it starts. */
public final class FileMover {
    private final UndoLogStore store;

    /** Uses the same journal as the undo manager. */
    public FileMover(UndoLogStore store) { this.store = store; }

    /** Executes only the supplied preview destinations under an exclusive journal lock. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves) throws IOException {
        return organize(folder, moves, percent -> {});
    }

    /** Executes approved moves and reports the percentage of rows processed. */
    public OrganizeResult organize(Path folder, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        var selected = List.copyOf(moves);
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> execute(new SafetyChecker().validate(folder), selected, progress));
    }

    private OrganizeResult execute(Path root, List<PlannedMove> moves, IntConsumer progress) throws IOException {
        Batch prior = store.read();
        List<Entry> entries = new ArrayList<>();
        int skipped = 0, failed = 0;
        int processed = 0;
        for (var move : moves) {
            try {
                Path source = move.source(), target = move.destination();
                Entry intent;
                try {
                    validatePaths(root, source, target);
                    if (new CategoryClassifier().classify(source) != move.category()
                            || !source.getParent().equals(root)
                            || !target.getParent().equals(root.resolve(move.category().folderName()))
                            || unavailable(source, store.path()) || Files.exists(target, NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    }
                    Path directory = target.getParent();
                    if (Files.notExists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory);
                    checkDirectory(directory);
                    if (!Files.getFileStore(source).equals(Files.getFileStore(directory))) {
                        skipped++;
                        continue;
                    }
                    intent = new Entry(source.toString(), target.toString(), Instant.now().toString(),
                            signature(source), false);
                } catch (IOException | SecurityException | OverlappingFileLockException e) {
                    failed++;
                    continue;
                }
                entries.add(intent);
                store.save(new Batch(root.toString(), entries));
                try {
                    checkDirectory(target.getParent());
                    Files.move(source, target); // No replacement and no cross-volume fallback.
                } catch (FileAlreadyExistsException e) {
                    skipped++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                } catch (IOException | SecurityException e) {
                    // Preserve an ambiguous outcome for recovery if the source disappeared.
                    if (!Files.exists(source, NOFOLLOW_LINKS)) throw new IOException("Check undo history before continuing.", e);
                    failed++;
                    entries.removeLast();
                    store.save(entries.isEmpty() ? prior : new Batch(root.toString(), entries));
                    continue;
                }
                entries.set(entries.size()-1, new Entry(intent.original(), intent.destination(),
                        intent.timestamp(), intent.signature(), true));
                store.save(new Batch(root.toString(), entries));
            } finally {
                progress.accept((int) (++processed * 100L / moves.size()));
            }
        }
        return new OrganizeResult(entries.size(), skipped, failed);
    }

    static void validatePaths(Path root, Path source, Path target) throws IOException {
        if (!root.equals(source.getParent()) || !source.equals(source.normalize())
                || !target.equals(target.normalize()) || target.getParent() == null
                || !root.resolve(new CategoryClassifier().classify(source).folderName()).equals(target.getParent()))
            throw new IOException("Move paths do not match the selected folder.");
    }

    static void checkDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new IOException("Category folder changed or is linked.");
    }

    static boolean unavailable(Path file, Path journal) throws IOException {
        return FolderScanner.exclusionReason(file, journal,
                Files.exists(journal) ? journal.toRealPath() : journal) != null;
    }

    static String signature(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Expected a regular file.");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return attributes.fileKey() + "/" + attributes.creationTime() + "/" +
                attributes.lastModifiedTime() + "/" + HexFormat.of().formatHex(digest.digest());
    }
}
```

### src/main/java/com/smartfileorganizer/core/UndoManager.java

```java
package com.smartfileorganizer.core;

import com.smartfileorganizer.model.OrganizeResult;
import com.smartfileorganizer.storage.UndoLogStore;
import com.smartfileorganizer.storage.UndoLogStore.Batch;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.IntConsumer;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Restores unchanged files from the persisted batch in reverse order. */
public final class UndoManager {
    private final UndoLogStore store;

    /** Opens the same journal used by the organizer. */
    public UndoManager(UndoLogStore store) { this.store = store; }

    /** Reports whether there are outstanding journal entries. */
    public boolean hasUndo() throws IOException {
        return !store.read().entries().isEmpty();
    }

    /** Restores eligible files and keeps unresolved entries available for retry. */
    public OrganizeResult undo() throws IOException {
        return undo(percent -> {});
    }

    /** Restores eligible files and reports the percentage of entries processed. */
    public OrganizeResult undo(IntConsumer progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        return store.exclusive(() -> restore(progress));
    }

    private OrganizeResult restore(IntConsumer progress) throws IOException {
        Batch batch = store.read();
        var entries = new ArrayList<>(batch.entries());
        if (entries.isEmpty()) return new OrganizeResult(0, 0, 0);
        Path root = new SafetyChecker().validate(Path.of(batch.folder()));
        int restored = 0, skipped = 0, failed = 0;
        int total = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            try {
                var entry = entries.get(i);
                try {
                    Path original = Path.of(entry.original()), moved = Path.of(entry.destination());
                    FileMover.validatePaths(root, original, moved);
                    if (original.equals(store.path()) || moved.equals(store.path())) {
                        throw new IOException("Invalid journal path.");
                    }
                    if (Files.notExists(moved, NOFOLLOW_LINKS) && Files.isRegularFile(original, NOFOLLOW_LINKS)
                            && entry.signature().equals(FileMover.signature(original))) {
                        // The move never ran, or a previous undo completed before its journal update.
                        skipped++;
                    } else if (!Files.notExists(original, NOFOLLOW_LINKS) || Files.notExists(moved, NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    } else {
                        FileMover.checkDirectory(moved.getParent());
                        if (FileMover.unavailable(moved, store.path())
                                || !entry.signature().equals(FileMover.signature(moved))
                                || !Files.getFileStore(moved).equals(Files.getFileStore(root))) {
                            skipped++;
                            continue;
                        }
                        Files.move(moved, original);
                        restored++;
                    }
                } catch (FileAlreadyExistsException e) {
                    skipped++;
                    continue;
                } catch (IOException | SecurityException | InvalidPathException | OverlappingFileLockException e) {
                    failed++;
                    continue;
                }
                entries.remove(i);
                store.save(new Batch(batch.folder(), entries));
            } finally {
                progress.accept((int) ((total - i) * 100L / total));
            }
        }
        return new OrganizeResult(restored, skipped, failed);
    }
}
```

### src/test/java/com/smartfileorganizer/ui/DesktopFlowTest.java

```java
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
```

## 4. How to run and verify
From the project directory with Maven and JDK 21 installed:
```powershell
mvn clean verify
java -jar target/smart-file-organizer.jar
```
Expected on a Windows desktop: 84 passing tests, BUILD SUCCESS, then the Smart File Organizer window. The GUI test opens temporary windows and uses only temporary data. On headless systems the GUI test is skipped.

To run the compiled version already verified here without Maven:
```powershell
& "C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "target/phase5;target/gson.jar;target/flatlaf.jar" com.smartfileorganizer.Main
```
Verification used JDK 25 with --release 21 and the JUnit standalone runner. All 84 tests passed. After the destination display change, both desktop tests passed again. Maven packaging and execution on JDK 21 remain unverified.

## 5. How it works
Main applies FlatLaf and starts the window on the Swing event thread. OrganizerService exposes small preview, organize, and undo operations to the interface. It keeps disk operations outside the UI layer.
PreviewTableModel stores checkbox state in model order. JTable sorting changes only the displayed row order, so excluding a sorted row still excludes the correct file. Destination names are relative to the selected folder.
MainFrame runs service calls in SwingWorker.doInBackground and updates controls in done. Scanning uses an indeterminate indicator. File operations report the percentage of rows processed, including skipped and failed rows. Controls are disabled while work runs, and closing the window waits for the operation to finish.
The Organize confirmation shows the selected file and destination-folder counts. It explains when starting another organization will replace the prior undo history. Undo availability is read from the persisted journal in the background. Each operation clears the old preview, shows a result summary, and refreshes the selected folder.
Friendly error dialogs accompany detailed application logs in the same app-data directory as the undo journal. Basic log setup was included now because error dialogs need a persistent diagnostic record. Remembering the last folder and further polish remain Phase 6 work.
The folder chooser uses the standard Swing API, whose selected-file object is converted immediately to a NIO Path. File scanning and move operations continue to use NIO.

## 6. Common errors
- Maven not recognized. Install Maven and add its bin directory to PATH, or use the compiled-version command above.
- Organize disabled. Choose a valid folder, wait for the preview, and include at least one file.
- Undo disabled. There are no saved undo entries, or the saved history could not be read. Read the error dialog and application log.
- Folder appears empty. Check Skipped files for hidden, protected, temporary, locked, and other excluded entries. Nested files are excluded from v1.
- FlatLaf warning on JDK 25. The compiled-version command includes the native-access flag needed by the installed newer runtime.
- Package build failure. Maven packaging remains unverified in this environment. The directly compiled application and desktop flow have passed.
