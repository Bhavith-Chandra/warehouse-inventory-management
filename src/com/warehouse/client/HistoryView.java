package com.warehouse.client;

import com.warehouse.model.TransactionLog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.time.LocalDateTime;

/**
 * "Transaction History" view: full audit log with type-coloured
 * delta column, filter by action type, and CSV export shortcut.
 */
public class HistoryView {

    private final InventoryClient app;
    private final VBox root;

    public HistoryView(InventoryClient app) {
        this.app = app;
        root = build();
    }

    public Node getNode() { return root; }

    @SuppressWarnings("unchecked")
    private VBox build() {
        Label title = new Label("Transaction History");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Full audit log of every stock change across all clients.");
        sub.getStyleClass().add("page-subtitle");
        VBox header = new VBox(2, title, sub);

        /* ── table ── */
        TableView<TransactionLog> table = new TableView<>(app.transactionItems);
        table.setPlaceholder(new Label("No transactions recorded yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<TransactionLog, String> tsCol = new TableColumn<>("Time");
        tsCol.setPrefWidth(148);
        tsCol.setCellValueFactory(d ->
            new javafx.beans.property.SimpleStringProperty(
                d.getValue().getTimestamp() == null ? ""
                : d.getValue().getTimestamp().format(InventoryClient.TIME_FMT)));
        tsCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                setStyle("-fx-text-fill: #6b7388; -fx-font-size: 11px;");
            }
        });

        TableColumn<TransactionLog, String> userCol = txCol("User", "performedBy", 100);

        TableColumn<TransactionLog, String> typeCol = new TableColumn<>("Action");
        typeCol.setPrefWidth(120);
        typeCol.setCellValueFactory(d ->
            new javafx.beans.property.SimpleStringProperty(
                d.getValue().getType().name()));
        typeCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Label badge = new Label(v);
                badge.getStyleClass().add(activityStyle(v));
                badge.setPadding(new Insets(2, 8, 2, 8));
                badge.setStyle(badge.getStyle() +
                    "-fx-background-radius: 999; -fx-font-size: 11px; -fx-font-weight: bold;");
                setGraphic(badge); setText(null);
            }
        });

        TableColumn<TransactionLog, String> prodCol = txCol("Product", "productName", 220);

        TableColumn<TransactionLog, Integer> deltaCol = new TableColumn<>("Δ Qty");
        deltaCol.setPrefWidth(70);
        deltaCol.setStyle("-fx-alignment: CENTER;");
        deltaCol.setCellValueFactory(new PropertyValueFactory<>("quantityChange"));
        deltaCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); return; }
                setText((v > 0 ? "+" : "") + v);
                setStyle("-fx-text-fill: " + (v >= 0 ? "#4ad9a4" : "#ff8f8f") +
                         "; -fx-font-weight: bold; -fx-alignment: CENTER;");
            }
        });

        TableColumn<TransactionLog, Integer> nowCol = new TableColumn<>("Now");
        nowCol.setPrefWidth(70);
        nowCol.setStyle("-fx-alignment: CENTER;");
        nowCol.setCellValueFactory(new PropertyValueFactory<>("resultingQuantity"));

        TableColumn<TransactionLog, String> reasonCol = txCol("Reason", "reason", 300);

        table.getColumns().addAll(tsCol, userCol, typeCol, prodCol, deltaCol, nowCol, reasonCol);

        /* ── toolbar ── */
        Label countLabel = new Label();
        countLabel.textProperty().bind(
            javafx.beans.binding.Bindings.size(app.transactionItems).asString("%d records"));
        countLabel.getStyleClass().add("subtle-text");

        Button refreshBtn = new Button("⟳  Refresh");
        refreshBtn.getStyleClass().addAll("button", "ghost");
        refreshBtn.setOnAction(e -> app.refreshTransactions());

        Button exportBtn = new Button("↓  Export CSV");
        exportBtn.getStyleClass().addAll("button", "ghost");
        exportBtn.setOnAction(e -> exportTransactionsCsv());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(10, countLabel, spacer, refreshBtn, exportBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, toolbar, table);
        card.getStyleClass().add("card");
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(card, Priority.ALWAYS);

        VBox page = new VBox(16, header, card);
        page.setPadding(new Insets(24));
        VBox.setVgrow(card, Priority.ALWAYS);
        return page;
    }

    private void exportTransactionsCsv() {
        if (app.transactionItems.isEmpty()) {
            Toast.warn("No transactions to export."); return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Save Transaction Log CSV");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("CSV files", "*.csv"));
        fc.setInitialFileName("transactions_" +
            LocalDateTime.now().format(java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmm")) + ".csv");
        java.io.File file = fc.showSaveDialog(null);
        if (file == null) return;
        try (java.io.PrintWriter pw = new java.io.PrintWriter(file)) {
            pw.println("ID,Timestamp,User,Action,Product,DeltaQty,ResultingQty,Reason");
            for (TransactionLog tx : app.transactionItems) {
                pw.printf("%d,%s,%s,%s,%s,%d,%d,%s%n",
                    tx.getId(),
                    tx.getTimestamp() != null ? tx.getTimestamp().format(InventoryClient.TIME_FMT) : "",
                    csvEsc(tx.getPerformedBy()),
                    tx.getType().name(),
                    csvEsc(tx.getProductName()),
                    tx.getQuantityChange(),
                    tx.getResultingQuantity(),
                    csvEsc(tx.getReason()));
            }
            Toast.success("Exported " + app.transactionItems.size() +
                    " records to " + file.getName());
        } catch (java.io.IOException e) {
            Toast.error("Export failed: " + e.getMessage());
        }
    }

    private static String activityStyle(String type) {
        return switch (type) {
            case "ADD"            -> "activity-action-add";
            case "REMOVE"         -> "activity-action-remove";
            case "ADJUST"         -> "activity-action-adjust";
            case "CREATE_PRODUCT" -> "activity-action-create";
            case "DELETE_PRODUCT" -> "activity-action-delete";
            default               -> "";
        };
    }

    private static String csvEsc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    @SuppressWarnings("unchecked")
    private TableColumn<TransactionLog, String> txCol(String label, String prop, int w) {
        TableColumn<TransactionLog, String> c = new TableColumn<>(label);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(w);
        return c;
    }
}
