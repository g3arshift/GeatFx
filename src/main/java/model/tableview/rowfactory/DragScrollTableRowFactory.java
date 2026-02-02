package model.tableview.rowfactory;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.MenuItem;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Callback;
import model.MessageType;
import model.result.Result;
import org.jetbrains.annotations.NotNull;
import view.popup.Popup;
import view.popup.TwoButtonChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;


/**
 * Copyright (C) 2026 Gear Shift Gaming - All Rights Reserved
 * You may use, distribute and modify this code under the terms of the GPL3 license.
 * <p>
 * You should have received a copy of the GPL3 license with
 * this file. If not, please write to: gearshift@gearshiftgaming.com.
 */

public class DragScrollTableRowFactory<T, R extends TableRow<T>> implements Callback<TableView<T>, TableRow<T>> {

    private final DataFormat serializedMimeType;
    private final List<T> selections;
    private TableRow<T> previousRow;

    //TODO: Ugh. I need to think about the broader design here and how I'm using this.
    Function<?> rowCreator;

    private enum RowBorderType {
        TOP,
        BOTTOM
    }

    public DragScrollTableRowFactory(DataFormat serializedMimeType, List<T> selections) {
        this.serializedMimeType = serializedMimeType;
        this.selections = selections;
    }

    @Override
    public R call(@NotNull TableView<T> table) {
        final R row = rowCreator.apply(table);

        final ContextMenu tableContextMenu = new ContextMenu();

        final MenuItem deleteSelectedRows = new MenuItem("Delete selected rows");
        deleteSelectedRows.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        deleteSelectedRows.setOnAction(_ -> deleteRow(table));

        final MenuItem selectAll = new MenuItem("Select all");
        selectAll.setOnAction(_ -> table.getSelectionModel().selectAll());

        tableContextMenu.getItems().addAll(deleteSelectedRows, selectAll);

        row.contextMenuProperty().bind(
                Bindings.when(Bindings.isNotNull(row.itemProperty()))
                        .then(tableContextMenu)
                        .otherwise((ContextMenu) null));

        table.setOnKeyPressed(event -> {
            List<T> selectedItems = table.getSelectionModel().getSelectedItems();
            if (event.getCode().equals(KeyCode.DELETE)) {
                if (!selectedItems.isEmpty()) {
                    deleteRow(table);
                }
            }
        });

        setupDragScrolling(table, row);
        return row;
    }

    private void setupDragScrolling(TableView<T> table, TableRow<T> row) {
        ScrollBar modTableVerticalScrollBar;
        modTableVerticalScrollBar = (ScrollBar) table.lookup(".scroll-bar:vertical");
        //Setup drag and drop reordering for the table
        row.setOnDragDetected(dragEvent -> {
            //Don't allow dragging if a sortOrder is applied, or if the sort order that's applied isn't an ascending sort on loadPriority
            if ((table.getSortOrder().isEmpty() || table.getSortOrder().getFirst().getId().equals("loadPriority")) && !row.isEmpty()) {
                    Integer index = row.getIndex();
                    selections.clear();

                    ObservableList<T> items = table.getSelectionModel().getSelectedItems();

                    selections.addAll(items);

                    Dragboard dragboard = row.startDragAndDrop(TransferMode.MOVE);
                    dragboard.setDragView(row.snapshot(null, null));
                    ClipboardContent clipboardContent = new ClipboardContent();
                    clipboardContent.put(serializedMimeType, index);
                    dragboard.setContent(clipboardContent);

                    dragEvent.consume();
                }

        });

        row.setOnDragOver(dragEvent -> {
            Dragboard dragboard = dragEvent.getDragboard();
            if (dragboard.hasContent(serializedMimeType) && row.getIndex() != ((Integer) dragboard.getContent(serializedMimeType))) {
                dragEvent.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                dragEvent.consume();
            }
        });

        row.setOnDragEntered(dragEvent -> {
            if (previousRow != null && !row.isEmpty()) {
                previousRow.setBorder(null);
            }

            if (!row.isEmpty()) {
                previousRow = row;
                modlistManagerView.setPreviousRow(previousRow);
            }

            if (dragEvent.getDragboard().hasContent(serializedMimeType)) {
                if (!row.isEmpty()) {
                    addBorderToRow(RowBorderType.TOP, row);
                }
            }
            dragEvent.consume();
        });

        row.setOnDragExited(dragEvent -> {
            //If we are not the last item and the row isn't blank, set it to null. Else, set a bottom border.
            if (!row.isEmpty() && previousRow.getItem().equals(uiService.getCurrentModList().getLast())) {
                //We don't want to add a border if the table isn't big enough to display all mods at once since we'll end up with a double border
                if (!modTableVerticalScrollBar.isVisible()) {
                    addBorderToRow(RowBorderType.BOTTOM, row);
                } else {
                    row.setBorder(null);
                }
            } else {
                row.setBorder(null);
            }
            dragEvent.consume();
        });

        row.setOnDragDropped(dragEvent -> {
            row.setBorder(null);
            Dragboard dragboard = dragEvent.getDragboard();

            if (dragboard.hasContent(serializedMimeType)) {
                int dropIndex;
                Mod mod = null;

                if (row.isEmpty()) {
                    dropIndex = uiService.getCurrentModList().size();
                } else {
                    dropIndex = row.getIndex();
                    mod = uiService.getCurrentModList().get(dropIndex);
                }

                int delta = 0;
                if (mod != null) {
                    while (selections.contains(mod)) {
                        delta = 1;
                        --dropIndex;
                        if (dropIndex < 0) {
                            mod = null;
                            dropIndex = 0;
                            break;
                        }
                        mod = uiService.getCurrentModList().get(dropIndex);
                    }
                }

                for (Mod m : selections) {
                    uiService.getCurrentModList().remove(m);
                }

                if (mod != null) {
                    dropIndex = uiService.getCurrentModList().indexOf(mod) + delta;
                } else if (dropIndex != 0) {
                    dropIndex = uiService.getCurrentModList().size();
                }

                table.getSelectionModel().clearSelection();

                for (Mod m : selections) {
                    uiService.getCurrentModList().add(dropIndex, m);
                    table.getSelectionModel().select(dropIndex);
                    dropIndex++;
                }
                dragEvent.setDropCompleted(true);
                selections.clear();

                modlistManagerHelper.setCurrentModListLoadPriority(table, uiService);

                //Redo our sort since our row order has changed
                table.sort();

				/*
					We shouldn't need this since currentModList which backs our table is an observable list backed by the currentModProfile.getModList,
					but for whatever reason the changes aren't propagating without this.
				 */
                //TODO: Look into why the changes don't propagate without setting it here. Indicative of a deeper issue or misunderstanding.
                //TODO: We might be able to fix this with the new memory model. Investigate.
                uiService.getCurrentModListProfile().setModList(uiService.getCurrentModList());
                Result<Void> updateModListLoadPriorityResult = uiService.updateModListLoadPriority();
                checkResult(updateModListLoadPriorityResult, "Failed to update mod list load priority. See the log for more information.");

                dragEvent.consume();
            }
        });

        row.setOnDragDone(dragEvent -> {
            if (modlistManagerView.getScrollTimeline() != null) modlistManagerView.getScrollTimeline().stop();

            // Remove any borders and perform clean-up actions here
            if (previousRow != null) previousRow.setBorder(null);
            dragEvent.consume();
        });

        //This is a dumb hack, but I can't get the row's height any other way
        if (modlistManagerView.getSingleTableRow() == null) {
            ChangeListener<Number> rowHeightListener = new ChangeListener<Number>() {
                @Override
                public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
                    if (newValue.doubleValue() > 0) {
                        modlistManagerView.setSingleTableRow(row);
                        row.heightProperty().removeListener(this);
                    }
                }
            };
            row.heightProperty().addListener(rowHeightListener);
        }
    }

    private void addBorderToRow(RowBorderType rowBorderType, @NotNull ModTableRow row) {
        if (!row.isEmpty() || (row.getIndex() <= uiService.getCurrentModList().size() && uiService.getCurrentModList().get(row.getIndex() - 1) != null)) {
            Color indicatorColor = Color.web(modlistManagerHelper.getSelectedCellBorderColor(uiService));
            Border dropIndicator;
            if (rowBorderType.equals(RowBorderType.TOP)) {
                dropIndicator = new Border(new BorderStroke(indicatorColor, indicatorColor, indicatorColor, indicatorColor,
                        BorderStrokeStyle.SOLID, BorderStrokeStyle.NONE, BorderStrokeStyle.NONE, BorderStrokeStyle.NONE,
                        CornerRadii.EMPTY, new BorderWidths(2), Insets.EMPTY));
            } else {
                dropIndicator = new Border(new BorderStroke(indicatorColor, indicatorColor, indicatorColor, indicatorColor,
                        BorderStrokeStyle.NONE, BorderStrokeStyle.NONE, BorderStrokeStyle.SOLID, BorderStrokeStyle.NONE,
                        CornerRadii.EMPTY, new BorderWidths(2), new Insets(0, 0, 2, 0)));
            }
            row.setBorder(dropIndicator);
        }
    }

    private void deleteRow(TableView<?> table) {
        TwoButtonChoice choice = Popup.displayYesNoDialog("Are you sure you want to delete these rows?", modlistManagerView.getStage(), model.MessageType.WARN);
        if (choice == TwoButtonChoice.YES) {
            final List<?> selectedMods = new ArrayList<>(table.getSelectionModel().getSelectedItems());


            if (!table.getSortOrder().isEmpty()) {
                TableColumn<?, ?> sortedColumn = table.getSortOrder().getFirst();
                TableColumn.SortType sortedColumnSortType = table.getSortOrder().getFirst().getSortType();
                sortedColumn.setSortType(null);
                table.refresh();
                sortedColumn.setSortType(sortedColumnSortType);
            }
        }
    }

    private void checkResult(Result<?> result, String failMessage) {
        if (result.isFailure()) {
            Popup.displaySimpleAlert(failMessage, MessageType.ERROR);
            throw new RuntimeException(result.getCurrentMessage());
        }
    }
}
