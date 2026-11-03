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
