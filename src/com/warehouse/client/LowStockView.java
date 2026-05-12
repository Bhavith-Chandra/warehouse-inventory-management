package com.warehouse.client;

import com.warehouse.model.Product;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.LocalDateTime;

/**
 * "Low Stock" view: products at or below their reorder level,
 * sorted by urgency (most depleted first), with a quick-restock shortcut.
 */
public class LowStockView {

    private final InventoryClient app;
    private final VBox root;

    public LowStockView(InventoryClient app) {
        this.app = app;
        root = build();
    }

    public Node getNode() { return root; }

    @SuppressWarnings("unchecked")
    private VBox build() {
        Label title = new Label("Low Stock Alerts");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Products at or below their reorder level — needs restocking.");
        sub.getStyleClass().add("page-subtitle");
        VBox header = new VBox(2, title, sub);

        /* ── count badge ── */
        Label countLabel = new Label();
        countLabel.textProperty().bind(
            Bindings.size(app.lowStockItems).asString("%d products need restocking"));
        countLabel.setStyle("-fx-text-fill: #ff9b9b; -fx-font-size: 13px; -fx-font-weight: bold;");

        /* ── table ── */
        TableView<Product> table = new TableView<>(app.lowStockItems);
        table.setPlaceholder(new Label("All stock levels are healthy."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<Product, String> skuCol = col("SKU", "sku", 100);
        TableColumn<Product, String> nameCol = col("Product Name", "name", 240);
        TableColumn<Product, String> catCol  = col("Category", "category", 120);

        TableColumn<Product, Integer> qtyCol = new TableColumn<>("In Stock");
        qtyCol.setPrefWidth(90);
        qtyCol.setStyle("-fx-alignment: CENTER;");
        qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        qtyCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setGraphic(null); setText(null); return; }
                Label pill = new Label(String.valueOf(v));
                pill.getStyleClass().addAll("qty-pill", v == 0 ? "zero" : "low");
                setGraphic(pill); setText(null);
            }
        });

        TableColumn<Product, Integer> reorderCol = new TableColumn<>("Reorder At");
        reorderCol.setPrefWidth(90);
        reorderCol.setStyle("-fx-alignment: CENTER;");
        reorderCol.setCellValueFactory(new PropertyValueFactory<>("reorderLevel"));

        TableColumn<Product, Integer> gapCol = new TableColumn<>("Deficit");
        gapCol.setPrefWidth(80);
        gapCol.setStyle("-fx-alignment: CENTER;");
        gapCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        gapCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Integer qty, boolean empty) {
                super.updateItem(qty, empty);
                if (empty || qty == null || getTableRow().getItem() == null) {
                    setText(null); return;
                }
                int deficit = getTableRow().getItem().getReorderLevel() - qty;
                setText(deficit > 0 ? "-" + deficit : "0");
                setStyle("-fx-text-fill: " + (deficit > 0 ? "#ff8f8f" : "#4ad9a4") + ";");
            }
        });

        TableColumn<Product, String> locCol = col("Location", "location", 90);

        TableColumn<Product, LocalDateTime> updCol = new TableColumn<>("Last Updated");
        updCol.setPrefWidth(148);
        updCol.setCellValueFactory(new PropertyValueFactory<>("lastUpdated"));
        updCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(LocalDateTime v, boolean empty) {
                super.updateItem(v, empty);
                setText((empty || v == null) ? null : v.format(InventoryClient.TIME_FMT));
                setStyle("-fx-text-fill: #6b7388; -fx-font-size: 11px;");
            }
        });

        TableColumn<Product, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(130);
        actionCol.setCellFactory(c -> new TableCell<>() {
            private final Button btn = new Button("Restock");
            {
                btn.getStyleClass().addAll("button", "success");
                btn.setStyle("-fx-font-size: 11px; -fx-padding: 4 12 4 12;");
                btn.setOnAction(e -> {
                    Product p = getTableRow().getItem();
                    if (p != null) openRestockDialog(p);
                });
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : btn);
            }
        });

        table.getColumns().addAll(
            skuCol, nameCol, catCol, qtyCol, reorderCol,
            gapCol, locCol, updCol, actionCol);

        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("low-stock");
                if (!empty && p != null) getStyleClass().add("low-stock");
            }
        });

        Button refreshBtn = new Button("⟳  Refresh");
        refreshBtn.getStyleClass().addAll("button", "ghost");
        refreshBtn.setOnAction(e -> app.refreshLowStock());

        HBox toolbar = new HBox(10, countLabel, new Region(), refreshBtn);
        HBox.setHgrow(toolbar.getChildren().get(1), Priority.ALWAYS);
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

    private void openRestockDialog(Product p) {
        TextInputDialog dlg = new TextInputDialog(String.valueOf(p.getReorderLevel() * 2));
        dlg.setTitle("Restock: " + p.getName());
        dlg.setHeaderText("How many units to add?");
        dlg.setContentText("Quantity to receive:");
        dlg.showAndWait().ifPresent(val -> {
            try {
                int qty = Integer.parseInt(val.trim());
                if (qty <= 0) { Toast.warn("Quantity must be positive."); return; }
                app.connection.send(new com.warehouse.protocol.Request(
                        com.warehouse.protocol.Request.Type.ADD_STOCK)
                    .put("productId", p.getId())
                    .put("quantity", qty)
                    .put("reason", "Restock from Low Stock view"))
                .whenComplete((resp, err) -> javafx.application.Platform.runLater(() -> {
                    if (err != null) { Toast.error(err.getMessage()); return; }
                    if (!resp.isOk()) { Toast.warn(resp.getMessage()); return; }
                    Toast.success("Restocked " + qty + " units of \"" + p.getName() + "\".");
                    app.refreshAll();
                }));
            } catch (NumberFormatException e) {
                Toast.warn("Invalid quantity.");
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> TableColumn<Product, T> col(String label, String prop, int width) {
        TableColumn<Product, T> c = new TableColumn<>(label);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(width);
        return c;
    }
}
