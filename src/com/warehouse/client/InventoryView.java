package com.warehouse.client;

import com.warehouse.model.Product;
import com.warehouse.model.User;
import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * "Inventory" view: searchable / filterable product table with
 * inline +/- quick-action buttons and a floating action toolbar.
 */
public class InventoryView {

    private final InventoryClient app;
    private final VBox root;

    private TableView<Product> table;
    private TextField searchField;
    private FilteredList<Product> filteredItems;

    /* category filter chips */
    private final HBox chipBar = new HBox(8);
    private String activeCategory = null;

    public InventoryView(InventoryClient app) {
        this.app = app;
        filteredItems = new FilteredList<>(app.productItems, p -> true);
        root = build();
    }

    public Node getNode() { return root; }

    /* ════════════════════════════════ build ════════════════════════════════ */

    private VBox build() {
        /* ── header ── */
        Label title = new Label("Inventory");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Manage products, prices, and stock levels.");
        sub.getStyleClass().add("page-subtitle");
        VBox headerText = new VBox(2, title, sub);

        /* ── search ── */
        searchField = new TextField();
        searchField.setPromptText("Search name, SKU, or category…");
        searchField.getStyleClass().addAll("text-field", "search-field");
        searchField.setPrefWidth(320);
        searchField.textProperty().addListener((obs, o, n) -> applyFilter(n));

        Button clearSearch = new Button("✕");
        clearSearch.getStyleClass().add("icon-button");
        clearSearch.setOnAction(e -> { searchField.clear(); activeCategory = null; rebuildChips(); });

        HBox searchBar = new HBox(8, searchField, clearSearch);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        /* ── toolbar buttons ── */
        boolean isAdmin = app.currentUser.getRole() == User.Role.ADMIN;

        Button addBtn    = new Button("+ New Product");
        addBtn.getStyleClass().addAll("button", "primary");
        addBtn.setDisable(!isAdmin);
        addBtn.setTooltip(new Tooltip("Add a new product to the catalogue (Ctrl+N)"));
        addBtn.setOnAction(e -> openProductEditor(null));

        Button editBtn   = new Button("Edit");
        editBtn.getStyleClass().addAll("button", "ghost");
        editBtn.setDisable(!isAdmin);
        editBtn.setOnAction(e -> {
            Product sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openProductEditor(sel);
        });

        Button delBtn = new Button("Remove");
        delBtn.getStyleClass().addAll("button", "danger");
        delBtn.setDisable(!isAdmin);
        delBtn.setOnAction(e -> {
            Product sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmAndRemove(sel);
        });

        Button refreshBtn = new Button("⟳  Refresh");
        refreshBtn.getStyleClass().addAll("button", "ghost");
        refreshBtn.setTooltip(new Tooltip("Refresh product list (Ctrl+R)"));
        refreshBtn.setOnAction(e -> app.refreshProducts());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, searchBar, spacer, refreshBtn, addBtn, editBtn, delBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        /* ── chip bar ── */
        chipBar.setPadding(new Insets(0, 0, 8, 0));
        chipBar.setAlignment(Pos.CENTER_LEFT);

        /* ── table ── */
        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        /* ── stats label ── */
        Label stats = new Label();
        filteredItems.addListener((javafx.collections.ListChangeListener<Product>) c -> {
            long low = filteredItems.stream().filter(Product::isLowStock).count();
            stats.setText(filteredItems.size() + " products  •  " + low + " low stock");
            stats.setStyle("-fx-text-fill: " + (low > 0 ? "#ffcc66" : "#8a93a6") +
                           "; -fx-font-size: 11.5px;");
        });

        VBox card = new VBox(toolbar, chipBar, table, stats);
        card.getStyleClass().add("card");
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(card, Priority.ALWAYS);

        VBox page = new VBox(16, headerText, card);
        page.setPadding(new Insets(24));
        VBox.setVgrow(card, Priority.ALWAYS);
        return page;
    }

    /* ════════════════════════════════ table ════════════════════════════════ */

    @SuppressWarnings("unchecked")
    private TableView<Product> buildTable() {
        TableView<Product> tv = new TableView<>(filteredItems);
        tv.setPlaceholder(new Label("No products found."));

        /* ID */
        TableColumn<Product, Integer> idCol = col("ID", "id", 50);
        idCol.setStyle("-fx-alignment: CENTER;");

        /* SKU */
        TableColumn<Product, String> skuCol = col("SKU", "sku", 100);

        /* Name */
        TableColumn<Product, String> nameCol = col("Name", "name", 230);

        /* Category */
        TableColumn<Product, String> catCol = col("Category", "category", 120);

        /* Qty — pill style */
        TableColumn<Product, Integer> qtyCol = new TableColumn<>("Qty");
        qtyCol.setPrefWidth(80);
        qtyCol.setStyle("-fx-alignment: CENTER;");
        qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        qtyCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setGraphic(null); return; }
                Label pill = new Label(String.valueOf(v));
                pill.getStyleClass().add("qty-pill");
                if (v == 0) pill.getStyleClass().add("zero");
                else {
                    Product p = getTableRow().getItem();
                    if (p != null && p.isLowStock()) pill.getStyleClass().add("low");
                }
                setGraphic(pill);
                setText(null);
            }
        });

        /* Reorder */
        TableColumn<Product, Integer> reorderCol = col("Reorder", "reorderLevel", 75);

        /* Price */
        TableColumn<Product, Double> priceCol = new TableColumn<>("Price");
        priceCol.setPrefWidth(85);
        priceCol.setCellValueFactory(new PropertyValueFactory<>("unitPrice"));
        priceCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText((empty || v == null) ? null : String.format("$%.2f", v));
            }
        });

        /* Location */
        TableColumn<Product, String> locCol = col("Location", "location", 90);

        /* Stock action buttons: Add / Remove with quantity dialog */
        TableColumn<Product, Void> actCol = new TableColumn<>("Stock");
        actCol.setPrefWidth(150);
        actCol.setStyle("-fx-alignment: CENTER;");
        actCol.setCellFactory(c -> new TableCell<>() {
            private final Button addBtn    = new Button("Add");
            private final Button removeBtn = new Button("Remove");
            {
                addBtn.getStyleClass().addAll("button", "success");
                addBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 10 3 10;");
                addBtn.setTooltip(new Tooltip("Add stock to this product"));

                removeBtn.getStyleClass().addAll("button", "danger");
                removeBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 10 3 10;");
                removeBtn.setTooltip(new Tooltip("Remove stock from this product"));

                addBtn.setOnAction(e -> {
                    Product p = getTableRow().getItem();
                    if (p != null) openStockDialog(p, true);
                });
                removeBtn.setOnAction(e -> {
                    Product p = getTableRow().getItem();
                    if (p != null) openStockDialog(p, false);
                });
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                removeBtn.setDisable(getTableRow().getItem().getQuantity() == 0);
                HBox box = new HBox(6, addBtn, removeBtn);
                box.setAlignment(Pos.CENTER);
                setGraphic(box);
            }
        });

        /* Updated */
        TableColumn<Product, LocalDateTime> updCol = new TableColumn<>("Updated");
        updCol.setPrefWidth(148);
        updCol.setCellValueFactory(new PropertyValueFactory<>("lastUpdated"));
        updCol.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(LocalDateTime v, boolean empty) {
                super.updateItem(v, empty);
                setText((empty || v == null) ? null : v.format(InventoryClient.TIME_FMT));
                setStyle("-fx-text-fill: #6b7388; -fx-font-size: 11px;");
            }
        });

        tv.getColumns().addAll(
            idCol, skuCol, nameCol, catCol, qtyCol,
            reorderCol, priceCol, locCol, actCol, updCol);

        /* Row colour: low-stock gets a reddish tint via CSS class */
        tv.setRowFactory(t -> new TableRow<>() {
            @Override protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("low-stock");
                if (!empty && p != null && p.isLowStock()) {
                    getStyleClass().add("low-stock");
                }
            }
        });

        return tv;
    }

    /* ════════════════════════════════ filtering / chips ════════════════════════════════ */

    private void applyFilter(String text) {
        String lo = text == null ? "" : text.toLowerCase();
        filteredItems.setPredicate(p -> {
            boolean matchesText = lo.isEmpty()
                || (p.getName()     != null && p.getName().toLowerCase().contains(lo))
                || (p.getSku()      != null && p.getSku().toLowerCase().contains(lo))
                || (p.getCategory() != null && p.getCategory().toLowerCase().contains(lo))
                || (p.getLocation() != null && p.getLocation().toLowerCase().contains(lo));
            boolean matchesCat = activeCategory == null
                || activeCategory.equals(p.getCategory());
            return matchesText && matchesCat;
        });
    }

    void onProductsRefreshed() {
        rebuildChips();
        applyFilter(searchField.getText());
    }

    private void rebuildChips() {
        chipBar.getChildren().clear();
        Button all = chip("All");
        all.setOnAction(e -> { activeCategory = null; all.getStyleClass().add("active"); applyFilter(searchField.getText()); rebuildChips(); });
        if (activeCategory == null) all.getStyleClass().add("active");
        chipBar.getChildren().add(all);

        app.productItems.stream()
            .map(Product::getCategory)
            .filter(c -> c != null && !c.isBlank())
            .distinct().sorted()
            .forEach(cat -> {
                Button b = chip(cat);
                if (cat.equals(activeCategory)) b.getStyleClass().add("active");
                b.setOnAction(e -> { activeCategory = cat; applyFilter(searchField.getText()); rebuildChips(); });
                chipBar.getChildren().add(b);
            });
    }

    private Button chip(String label) {
        Button b = new Button(label);
        b.getStyleClass().add("chip");
        return b;
    }

    void focusSearch() { searchField.requestFocus(); }
    /* ════════ stock quantity dialog (proposal §2.1: add/remove forms) ════════ */

    /**
     * Opens a modal dialog asking how many units to add or remove, plus an
     * optional reason, then sends ADD_STOCK / REMOVE_STOCK to the server.
     * This is the "add/update/remove form" called out in the proposal.
     */
    private void openStockDialog(Product p, boolean isAdd) {
        String action = isAdd ? "Add Stock" : "Remove Stock";
        String verb   = isAdd ? "Add"        : "Remove";

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(action + " — " + p.getName());
        dlg.setHeaderText(
            (isAdd ? "Receive units into stock" : "Remove units from stock")
            + "\nProduct: " + p.getName() + "  (SKU: " + p.getSku() + ")"
            + "\nCurrent stock: " + p.getQuantity());
        dlg.initModality(Modality.APPLICATION_MODAL);

        ButtonType applyBtn = new ButtonType(verb, ButtonType.OK.getButtonData());
        dlg.getDialogPane().getButtonTypes().addAll(applyBtn, ButtonType.CANCEL);

        Spinner<Integer> qtySpinner = new Spinner<>(1,
            isAdd ? 100_000 : Math.max(p.getQuantity(), 1), 1);
        qtySpinner.setEditable(true);
        qtySpinner.setPrefWidth(130);

        TextField reasonField = new TextField();
        reasonField.setPromptText("Reference or reason (optional)");

        GridPane grid = InventoryClient.formGrid();
        grid.add(new Label("Quantity"), 0, 0); grid.add(qtySpinner,  1, 0);
        grid.add(new Label("Reason"),   0, 1); grid.add(reasonField, 1, 1);
        if (!isAdd) {
            Label avail = new Label("Available: " + p.getQuantity() + " units");
            avail.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
            grid.add(avail, 1, 2);
        }
        dlg.getDialogPane().setContent(grid);
        Platform.runLater(qtySpinner::requestFocus);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != applyBtn) return;
            int qty = qtySpinner.getValue();
            if (qty <= 0) { Toast.warn("Quantity must be at least 1."); return; }
            if (!isAdd && qty > p.getQuantity()) {
                Toast.warn("Cannot remove " + qty +
                        " — only " + p.getQuantity() + " units in stock.");
                return;
            }
            String reason = reasonField.getText().trim().isEmpty()
                    ? action + " from inventory view"
                    : reasonField.getText().trim();
            Request req = new Request(
                    isAdd ? Request.Type.ADD_STOCK : Request.Type.REMOVE_STOCK)
                .put("productId", p.getId())
                .put("quantity", qty)
                .put("reason", reason);

            app.connection.send(req).whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null) { Toast.error("Operation failed: " + err.getMessage()); return; }
                if (!resp.isOk()) { Toast.warn(resp.getMessage()); return; }
                Product updated = (Product) resp.getPayload();
                Toast.success(verb + " " + qty + " units of \"" + p.getName() +
                        "\" — now " + updated.getQuantity() + " in stock.");
                app.refreshAll();
            }));
        });
    }

    /* ════════════════════════════════ product editor dialog ════════════════════════════════ */

    void openProductEditor(Product existing) {
        boolean editing = existing != null;
        Dialog<Product> dlg = new Dialog<>();
        dlg.setTitle(editing ? "Edit Product" : "New Product");
        dlg.setHeaderText(editing ? "Edit " + existing.getName() : "Register a new product");
        dlg.initModality(Modality.APPLICATION_MODAL);
        ButtonType saveBtn = new ButtonType("Save", ButtonType.OK.getButtonData());
        dlg.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        TextField sku      = InventoryClient.styledField(editing ? existing.getSku() : "");
        TextField name     = InventoryClient.styledField(editing ? existing.getName() : "");
        TextField category = InventoryClient.styledField(editing ? existing.getCategory() : "");
        Spinner<Integer> qty = new Spinner<>(0, Integer.MAX_VALUE,
            editing ? existing.getQuantity() : 0);
        qty.setEditable(true); qty.setDisable(editing);
        Spinner<Integer> reorder = new Spinner<>(0, Integer.MAX_VALUE,
            editing ? existing.getReorderLevel() : 10);
        reorder.setEditable(true);
        TextField price = InventoryClient.styledField(
            editing ? String.format("%.2f", existing.getUnitPrice()) : "0.00");
        TextField loc = InventoryClient.styledField(editing ? existing.getLocation() : "");

        GridPane g = InventoryClient.formGrid();
        int r = 0;
        g.add(new Label("SKU"),        0, r); g.add(sku,     1, r++);
        g.add(new Label("Name"),       0, r); g.add(name,    1, r++);
        g.add(new Label("Category"),   0, r); g.add(category,1, r++);
        g.add(new Label("Quantity"),   0, r); g.add(qty,     1, r++);
        g.add(new Label("Reorder Lvl"),0, r); g.add(reorder, 1, r++);
        g.add(new Label("Unit Price"), 0, r); g.add(price,   1, r++);
        g.add(new Label("Location"),   0, r); g.add(loc,     1, r++);
        if (editing) {
            Label hint = new Label("Use Stock Operations to change quantity.");
            hint.getStyleClass().add("subtle-text");
            g.add(hint, 0, r, 2, 1);
        }
        dlg.getDialogPane().setContent(g);
        Platform.runLater(name::requestFocus);

        dlg.setResultConverter(bt -> {
            if (bt != saveBtn) return null;
            Product p = editing ? existing : new Product();
            p.setSku(sku.getText().trim());
            p.setName(name.getText().trim());
            p.setCategory(category.getText().trim());
            if (!editing) p.setQuantity(qty.getValue());
            p.setReorderLevel(reorder.getValue());
            try { p.setUnitPrice(Double.parseDouble(price.getText().trim())); }
            catch (NumberFormatException e) { p.setUnitPrice(0); }
            p.setLocation(loc.getText().trim());
            return p;
        });

        Optional<Product> result = dlg.showAndWait();
        if (result.isEmpty()) return;
        Product p = result.get();
        if (p.getSku().isBlank() || p.getName().isBlank()) {
            Toast.warn("SKU and Name are required."); return;
        }

        Request req = new Request(editing ? Request.Type.UPDATE_PRODUCT
                                          : Request.Type.ADD_PRODUCT)
                .put("product", p);
        app.connection.send(req).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) { Toast.error("Save failed: " + err.getMessage()); return; }
            if (!resp.isOk()) { Toast.error("Save failed: " + resp.getMessage()); return; }
            Toast.success((editing ? "Updated" : "Created") + " product \"" + p.getName() + "\"");
            app.refreshAll();
        }));
    }

    /* ════════════════════════════════ remove ════════════════════════════════ */

    private void confirmAndRemove(Product p) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setHeaderText("Remove \"" + p.getName() + "\"?");
        a.setContentText("This will archive all its transaction history.\nThis cannot be undone.");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;

        app.connection.send(new Request(Request.Type.REMOVE_PRODUCT).put("id", p.getId()))
            .whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null) { Toast.error("Remove failed: " + err.getMessage()); return; }
                if (!resp.isOk()) { Toast.error("Remove failed: " + resp.getMessage()); return; }
                Toast.success("Removed " + p.getName() + " from catalogue.");
                app.refreshAll();
            }));
    }

    /* ── helpers ── */
    @SuppressWarnings("unchecked")
    private <T> TableColumn<Product, T> col(String label, String prop, int width) {
        TableColumn<Product, T> c = new TableColumn<>(label);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(width);
        return c;
    }
}