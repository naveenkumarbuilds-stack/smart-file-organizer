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
    private boolean historyReadable;
    private boolean operationFailed;

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
        runTask("Loading saved settings...", true, report -> service.startup(), saved -> {
            if (saved.folder().isPresent()) {
                chosenFolder = saved.folder().get();
                folder.setText(chosenFolder.toString());
                scan();
            } else {
                status.setText(saved.notice().isEmpty() ? "Choose a folder to preview its files." : saved.notice());
            }
        });
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
            if (!result.notice().isEmpty()) {
                JOptionPane.showMessageDialog(this, result.notice(), "Folder setting could not be saved",
                        JOptionPane.WARNING_MESSAGE);
            }
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
        if (restoring && result.skipped() + result.failed() > 0) {
            message += "\nUnresolved undo entries remain saved. Check for changed files or occupied original names.";
        } else if (!restoring && result.skipped() + result.failed() > 0) {
            message += "\nSome files were unavailable or their destinations changed. Refresh to review the remaining files.";
        }
        LOG.info(title + ": " + result);
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
        if (preview != null && !busy && !operationFailed) {
            status.setText(model.selectedMoves().size() + " of " + model.getRowCount()
                    + " files selected; " + preview.skipped().size() + " entries excluded.");
        }
    }

    private void updateButtons() {
        choose.setEnabled(!busy);
        refresh.setEnabled(!busy && chosenFolder != null);
        organize.setEnabled(!busy && historyReadable && preview != null && !model.selectedMoves().isEmpty());
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
        operationFailed = false;
        progress.setString(null);
        LOG.info(message);
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
                historyReadable = historyFailure == null;
                progress.setIndeterminate(false);
                model.setEditable(true);
                try {
                    T result = get();
                    progress.setValue(100);
                    if (historyFailure != null) showError(historyFailure);
                    completed.accept(result);
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
        operationFailed = true;
        progress.setString("Check the message");
        LOG.log(Level.WARNING, "Organizer operation failed", exception);
        status.setText("Operation could not finish. Refresh the preview or try Undo.");
        String message = FriendlyErrors.message(exception);
        JOptionPane.showMessageDialog(this, message, "Unable to complete operation", JOptionPane.ERROR_MESSAGE);
    }
}
