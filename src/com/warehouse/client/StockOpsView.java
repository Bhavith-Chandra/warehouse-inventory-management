package com.warehouse.client;

import com.warehouse.model.Product;
import com.warehouse.protocol.Request;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * "Stock Operations" view: a clean form for receiving, shipping, or
 * cycle-count adjustments, with live stock preview.
 */
public class StockOpsView {

    private final InventoryClient app;
    private final VBox root;

    private final ComboBox<Product> productCombo = new ComboBox<>();
    private final Spinner<Integer>  qtySpinner   = new Spinner<>(1, 100_000, 1);
    private final RadioButton       addRadio      = new RadioButton("Add  (receiving / replenishment)");
    private final RadioButton       removeRadio   = new RadioButton("Remove  (shipping / sale)");
    private final RadioButton       setRadio      = new RadioButton("Set absolute  (cycle count)");
    private final TextField         reasonField   = new TextField();
    private final Label             previewLabel  = new Label("");
    private final Button            applyBtn      = new Button("Apply");

    public StockOpsView(InventoryClient app) {
        this.app = app;
        root = build();
    }

    public Node getNode() { return root; }

    void onProductsRefreshed() {
        Product sel = productCombo.getSelectionModel().getSelectedItem();
        productCombo.setItems(app.productItems);
        if (sel != null) {
            app.productItems.stream()
                .filter(p -> p.getId() == sel.getId())
                .findFirst().ifPresent(p -> {
                    productCombo.getSelectionModel().select(p);
                    updatePreview(p);
                });
        }
    }

    /* ════════════════════════════════ build ════════════════════════════════ */

    private VBox build() {
        Label title = new Label("Stock Operations");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Receive, ship, or correct product stock levels.");
        sub.getStyleClass().add("page-subtitle");
        VBox header = new VBox(2, title, sub);

        /* product selector */
        productCombo.setItems(app.productItems);
        productCombo.setPromptText("Choose a product…");
        productCombo.setPrefWidth(420);
        productCombo.setButtonCell(new ProductCell());
        productCombo.setCellFactory(c -> new ProductCell());
        productCombo.valueProperty().addListener((obs, o, n) -> updatePreview(n));

        /* direction radios */
        ToggleGroup grp = new ToggleGroup();
        addRadio.setToggleGroup(grp); addRadio.setSelected(true);
        removeRadio.setToggleGroup(grp);
        setRadio.setToggleGroup(grp);

        /* qty spinner */
        qtySpinner.setEditable(true);
        qtySpinner.setPrefWidth(140);

        /* reason */
        reasonField.setPromptText("Reference number or note (optional)");
        reasonField.setPrefWidth(420);

        /* apply button */
        applyBtn.getStyleClass().addAll("button", "primary");
        applyBtn.setOnAction(e -> applyChange());

        /* preview card */
        previewLabel.getStyleClass().add("subtle-text");
        previewLabel.setWrapText(true);

        /* layout */
        GridPane form = InventoryClient.formGrid();
        int r = 0;
        form.add(new Label("Product"),    0, r); form.add(productCombo,    1, r++);
        form.add(new Label("Quantity"),   0, r); form.add(qtySpinner,      1, r++);
        form.add(new Label("Action"),     0, r);
        form.add(new VBox(8, addRadio, removeRadio, setRadio), 1, r++);
        form.add(new Label("Reason"),     0, r); form.add(reasonField,     1, r++);
        form.add(new Label(""),           0, r); form.add(applyBtn,        1, r++);
        form.add(new Label(""),           0, r); form.add(previewLabel,    1, r++);

        VBox formCard = new VBox(form);
        formCard.getStyleClass().add("card");
        formCard.setMaxWidth(680);

        /* context card: current stock */
        Label ctxTitle = new Label("How this works");
        ctxTitle.getStyleClass().add("section-title");
        String tips = """
                •  Add — increases stock (receiving new inventory).
                •  Remove — decreases stock (orders shipped).
                •  Set — overwrites to any value (cycle count correction).

                All operations are audit-logged immediately and pushed to \
                every connected client in real time. The server uses per-product \
                locking to prevent double-deductions under concurrent load.
                """;
        Label tipsLabel = new Label(tips);
        tipsLabel.setWrapText(true);
        tipsLabel.getStyleClass().add("subtle-text");
        VBox ctxCard = new VBox(12, ctxTitle, tipsLabel);
        ctxCard.getStyleClass().add("card");
        ctxCard.setPrefWidth(340);
        ctxCard.setMaxWidth(340);

        HBox row = new HBox(20, formCard, ctxCard);
        row.setAlignment(Pos.TOP_LEFT);

        VBox page = new VBox(20, header, row);
        page.setPadding(new Insets(24));
        return page;
    }

    /* ════════════════════════════════ logic ════════════════════════════════ */

    private void updatePreview(Product p) {
        if (p == null) { previewLabel.setText(""); return; }
        String stockInfo = "Current stock: " + p.getQuantity() +
                " units  (reorder at " + p.getReorderLevel() + ")";
        if (p.isLowStock()) {
            previewLabel.setStyle("-fx-text-fill: #ffcc66;");
            previewLabel.setText(stockInfo + "  ⚠ LOW STOCK");
        } else {
            previewLabel.setStyle("");
            previewLabel.setText(stockInfo);
        }
    }

    private void applyChange() {
        Product sel = productCombo.getSelectionModel().getSelectedItem();
        if (sel == null) { Toast.warn("Please select a product first."); return; }
        int amount = qtySpinner.getValue();
        String reason = reasonField.getText();

        Request req;
        if (addRadio.isSelected()) {
            req = new Request(Request.Type.ADD_STOCK)
                    .put("productId", sel.getId())
                    .put("quantity", amount).put("reason", reason);
        } else if (removeRadio.isSelected()) {
            req = new Request(Request.Type.REMOVE_STOCK)
                    .put("productId", sel.getId())
                    .put("quantity", amount).put("reason", reason);
        } else {
            req = new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", sel.getId())
                    .put("newQuantity", amount).put("reason", reason);
        }

        applyBtn.setDisable(true);
        applyBtn.setText("Applying…");
        app.connection.send(req).whenComplete((resp, err) -> Platform.runLater(() -> {
            applyBtn.setDisable(false);
            applyBtn.setText("Apply");
            if (err != null) { Toast.error(err.getMessage()); return; }
            if (!resp.isOk()) { Toast.warn(resp.getMessage()); return; }

            Product updated = (Product) resp.getPayload();
            String verb = addRadio.isSelected() ? "Added +" + amount
                        : removeRadio.isSelected() ? "Removed " + amount
                        : "Set to " + amount;
            Toast.success(verb + " units of \"" + sel.getName() +
                    "\"  ->  now " + updated.getQuantity() + " in stock.");
            reasonField.clear();
            app.refreshAll();
        }));
    }

    /* ── cell ── */
    private static class ProductCell extends ListCell<Product> {
        @Override protected void updateItem(Product p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) { setText(null); return; }
            String warn = p.isLowStock() ? "  ⚠" : "";
            setText(p.getSku() + "  —  " + p.getName() +
                    "  (" + p.getQuantity() + " in stock)" + warn);
        }
    }
}
