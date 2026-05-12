package com.warehouse.client;

import com.warehouse.model.Product;
import com.warehouse.model.TransactionLog;
import com.warehouse.model.User;
import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redesigned JavaFX client: sidebar navigation, dark theme, dashboard
 * with KPI cards + charts, inline table quick-actions, toast
 * notifications, category filter chips, and CSV export.
 */
public class InventoryClient extends Application {

    private static final Logger LOG = Logger.getLogger(InventoryClient.class.getName());
    static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ── network ── */
    ServerConnection connection;
    User currentUser;
    private Stage primaryStage;

    /* ── shared data ── */
    final ObservableList<Product> productItems =
            FXCollections.observableArrayList();
    final ObservableList<Product> lowStockItems =
            FXCollections.observableArrayList();
    final ObservableList<TransactionLog> transactionItems =
            FXCollections.observableArrayList();

    /* ── ui state ── */
    private BorderPane mainLayout;
    private StackPane  contentStack;          // overlaid by toasts
    private Button     activeNavBtn;
    private Label      statusDot;
    private Label      statusText;
    private DashboardView dashboardView;
    private InventoryView inventoryView;
    private StockOpsView  stockOpsView;
    private LowStockView  lowStockView;
    private HistoryView   historyView;

    /* ════════════════════════════════ lifecycle ════════════════════════════════ */

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        ConnectionDetails d = showConnectionDialog();
        if (d == null) { Platform.exit(); return; }

        try {
            connection = new ServerConnection(d.host, d.port);
            connection.connect();
        } catch (IOException e) {
            errDialog("Connection failed",
                    "Could not reach " + d.host + ":" + d.port + "\n\n" + e.getMessage());
            Platform.exit();
            return;
        }

        connection.setEventListener(this::onServerEvent);
        connection.setDisconnectListener(this::onDisconnect);

        if (!login(d.username, d.password)) {
            connection.close();
            Platform.exit();
            return;
        }

        try {
            connection.sendSync(new Request(Request.Type.SUBSCRIBE_UPDATES), 5_000);
        } catch (IOException | TimeoutException | InterruptedException ignored) { }

        buildMainUi();
        refreshAll();
    }

    @Override
    public void stop() {
        if (connection != null) connection.close();
    }

    /* ════════════════════════════════ connection / login ════════════════════════════════ */

    static class ConnectionDetails {
        String host; int port; String username; String password;
    }

    private ConnectionDetails showConnectionDialog() {
        Dialog<ConnectionDetails> dlg = new Dialog<>();
        dlg.setTitle("Warehouse IMS — Connect");
        dlg.setHeaderText("Connect to server");
        ButtonType connectBtn = new ButtonType("Connect", ButtonType.OK.getButtonData());
        dlg.getDialogPane().getButtonTypes().addAll(connectBtn, ButtonType.CANCEL);

        TextField host   = styledField("localhost");
        TextField port   = styledField("5555");
        TextField user   = styledField("admin");
        PasswordField pw = new PasswordField(); pw.setText("admin123");
        pw.getStyleClass().add("text-field");

        Label hint = new Label("Accounts:  admin / admin123  |  staff / staff123");
        hint.getStyleClass().add("subtle-text");

        GridPane g = formGrid();
        int r = 0;
        g.add(new Label("Host"),     0, r); g.add(host, 1, r++);
        g.add(new Label("Port"),     0, r); g.add(port, 1, r++);
        g.add(new Label("Username"), 0, r); g.add(user, 1, r++);
        g.add(new Label("Password"), 0, r); g.add(pw,   1, r++);
        g.add(hint, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);
        Platform.runLater(user::requestFocus);

        dlg.setResultConverter(bt -> {
            if (bt != connectBtn) return null;
            ConnectionDetails cd = new ConnectionDetails();
            cd.host = host.getText().trim();
            try { cd.port = Integer.parseInt(port.getText().trim()); }
            catch (NumberFormatException e) { cd.port = 5555; }
            cd.username = user.getText().trim();
            cd.password = pw.getText();
            return cd;
        });
        return dlg.showAndWait().orElse(null);
    }

    private boolean login(String username, String password) {
        try {
            Response r = connection.sendSync(
                new Request(Request.Type.LOGIN)
                    .put("username", username)
                    .put("password", password), 5_000);
            if (!r.isOk()) { errDialog("Login failed", r.getMessage()); return false; }
            currentUser = (User) r.getPayload();
            return true;
        } catch (IOException | TimeoutException | InterruptedException e) {
            errDialog("Login failed", e.getMessage()); return false;
        }
    }

    /* ════════════════════════════════ main UI ════════════════════════════════ */

    private void buildMainUi() {
        /* -- views -- */
        dashboardView = new DashboardView(currentUser.getUsername());
        inventoryView = new InventoryView(this);
        stockOpsView  = new StockOpsView(this);
        lowStockView  = new LowStockView(this);
        historyView   = new HistoryView(this);

        /* -- sidebar -- */
        VBox sidebar = buildSidebar();

        /* -- content + toast overlay -- */
        contentStack = new StackPane(dashboardView.getNode());
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        HBox.setHgrow(contentStack, Priority.ALWAYS);
        Toast.install(contentStack);

        /* -- root layout -- */
        mainLayout = new BorderPane();
        mainLayout.setLeft(sidebar);
        mainLayout.setCenter(contentStack);
        mainLayout.setBottom(buildStatusBar());

        /* -- scene + theme -- */
        Scene scene = new Scene(mainLayout, 1280, 780);
        scene.getStylesheets().add(
            getClass().getResource("styles.css").toExternalForm());
        wireKeyboardShortcuts(scene);

        primaryStage.setScene(scene);
        primaryStage.setTitle("Warehouse IMS — " +
                currentUser.getUsername() + " (" + currentUser.getRole() + ")");
        primaryStage.show();
    }

    /* ════════════════════════════════ sidebar ════════════════════════════════ */

    private VBox buildSidebar() {
        // Brand header
        Label brand = new Label("Warehouse IMS");
        brand.getStyleClass().add("brand-label");
        Label sub = new Label("Inventory Management");
        sub.getStyleClass().add("brand-subtitle");
        VBox brandBox = new VBox(2, brand, sub);
        brandBox.getStyleClass().add("brand-box");

        // Nav buttons — plain text, no icons
        Button dash  = navBtn("Dashboard");
        Button inv   = navBtn("Inventory");
        Button stock = navBtn("Stock Operations");
        Button low   = navBtn("Low Stock Alerts");
        Button hist  = navBtn("Transaction History");
        Button exp   = navBtn("Export CSV");

        dash .setOnAction(e -> navigate(dashboardView.getNode(), dash));
        inv  .setOnAction(e -> navigate(inventoryView.getNode(), inv));
        stock.setOnAction(e -> navigate(stockOpsView.getNode(),  stock));
        low  .setOnAction(e -> navigate(lowStockView.getNode(),  low));
        hist .setOnAction(e -> navigate(historyView.getNode(),   hist));
        exp  .setOnAction(e -> exportCsv());

        dash.getStyleClass().add("active");
        activeNavBtn = dash;

        Label navSection = new Label("PAGES");
        navSection.getStyleClass().add("nav-section-label");
        Label toolSection = new Label("TOOLS");
        toolSection.getStyleClass().add("nav-section-label");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox footerBox = new VBox(3,
            new Label(currentUser.getUsername() + " (" + currentUser.getRole() + ")"),
            new Label("CS6103  Spring 2026"));
        footerBox.getStyleClass().add("sidebar-footer");

        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.getChildren().addAll(
            brandBox,
            navSection, dash, inv, stock, low, hist,
            toolSection, exp,
            spacer, footerBox
        );
        return sidebar;
    }

    private Button navBtn(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("nav-button");
        return b;
    }

    private void navigate(Node view, Button navBtn) {
        if (activeNavBtn != null) activeNavBtn.getStyleClass().remove("active");
        activeNavBtn = navBtn;
        navBtn.getStyleClass().add("active");
        contentStack.getChildren().setAll(view);
        // Re-add toast container on top
        Toast.install(contentStack);
    }

    /* ════════════════════════════════ status bar ════════════════════════════════ */

    private HBox buildStatusBar() {
        statusDot  = new Label("Connected");
        statusDot.getStyleClass().add("status-text");
        statusDot.setStyle("-fx-text-fill: #16a34a;");

        statusText = new Label("");
        statusText.getStyleClass().add("status-text");

        Label shortcutHint = new Label(
            "Ctrl+R: Refresh   Ctrl+N: New Product   Ctrl+F: Search   Ctrl+E: Export");
        shortcutHint.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, statusDot, statusText, spacer, shortcutHint);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /* ════════════════════════════════ keyboard shortcuts ════════════════════════════════ */

    private void wireKeyboardShortcuts(Scene scene) {
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
            this::refreshAll);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN),
            () -> {
                if (currentUser.getRole() == User.Role.ADMIN)
                    inventoryView.openProductEditor(null);
            });
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
            () -> inventoryView.focusSearch());
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN),
            this::exportCsv);
    }

    /* ════════════════════════════════ data refresh ════════════════════════════════ */

    void refreshAll() {
        refreshProducts();
        refreshLowStock();
        refreshTransactions();
    }

    void refreshProducts() {
        connection.send(new Request(Request.Type.LIST_PRODUCTS))
            .whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null || !resp.isOk()) return;
                productItems.setAll(resp.<List<Product>>payloadAs());
                dashboardView.refresh(productItems, transactionItems);
                inventoryView.onProductsRefreshed();
                stockOpsView.onProductsRefreshed();
            }));
    }

    void refreshLowStock() {
        connection.send(new Request(Request.Type.LOW_STOCK_REPORT))
            .whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null || !resp.isOk()) return;
                lowStockItems.setAll(resp.<List<Product>>payloadAs());
            }));
    }

    void refreshTransactions() {
        connection.send(new Request(Request.Type.LIST_TRANSACTIONS).put("limit", 200))
            .whenComplete((resp, err) -> Platform.runLater(() -> {
                if (err != null || !resp.isOk()) return;
                transactionItems.setAll(resp.<List<TransactionLog>>payloadAs());
                dashboardView.refresh(productItems, transactionItems);
            }));
    }

    /* ════════════════════════════════ server events ════════════════════════════════ */

    private void onServerEvent(Response event) {
        Platform.runLater(() -> {
            refreshAll();
            String msg = switch (event.getEventType()) {
                case STOCK_CHANGED   -> "Stock updated";
                case PRODUCT_ADDED   -> "New product added";
                case PRODUCT_REMOVED -> "Product removed";
                case TRANSACTION_LOGGED -> "Transaction recorded";
                default -> "Live update";
            };
            setStatus(msg, "ok");
            Toast.info(msg + " by another user.");
            new Thread(() -> {
                try { TimeUnit.SECONDS.sleep(3); } catch (InterruptedException ignored) { }
                Platform.runLater(() -> setStatus("Connected", "ok"));
            }, "status-reset").start();
        });
    }

    private void onDisconnect(Throwable cause) {
        Platform.runLater(() -> {
            setStatus("Disconnected: " + cause.getMessage(), "bad");
            Toast.error("Connection lost: " + cause.getMessage());
        });
    }

    private void setStatus(String msg, String style) {
        if (statusDot == null) return;
        String colour = switch (style) {
            case "ok"   -> "#16a34a";
            case "warn" -> "#d97706";
            case "bad"  -> "#dc2626";
            default     -> "#6b7280";
        };
        statusDot.setText(msg);
        statusDot.setStyle("-fx-text-fill: " + colour + ";");
    }

    /* ════════════════════════════════ CSV export ════════════════════════════════ */

    private void exportCsv() {
        if (productItems.isEmpty()) { Toast.warn("No products to export."); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Inventory CSV");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        fc.setInitialFileName("warehouse_inventory_" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");
        File file = fc.showSaveDialog(primaryStage);
        if (file == null) return;
        try (PrintWriter pw = new PrintWriter(file)) {
            pw.println("ID,SKU,Name,Category,Quantity,ReorderLevel,UnitPrice,Location,LastUpdated");
            for (Product p : productItems) {
                pw.printf("%d,%s,%s,%s,%d,%d,%.2f,%s,%s%n",
                    p.getId(),
                    csvEscape(p.getSku()),
                    csvEscape(p.getName()),
                    csvEscape(p.getCategory()),
                    p.getQuantity(),
                    p.getReorderLevel(),
                    p.getUnitPrice(),
                    csvEscape(p.getLocation()),
                    p.getLastUpdated() != null ? p.getLastUpdated().format(TIME_FMT) : "");
            }
            Toast.success("Exported " + productItems.size() + " products to " + file.getName());
        } catch (IOException e) {
            Toast.error("Export failed: " + e.getMessage());
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /* ════════════════════════════════ helpers ════════════════════════════════ */

    static GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(14); g.setVgap(12); g.setPadding(new Insets(18));
        ColumnConstraints lbl = new ColumnConstraints(110);
        ColumnConstraints fld = new ColumnConstraints();
        fld.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(lbl, fld);
        return g;
    }

    static TextField styledField(String dflt) {
        TextField tf = new TextField(dflt);
        tf.getStyleClass().add("text-field");
        return tf;
    }

    static void errDialog(String header, String content) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText(header);
        a.setContentText(content);
        a.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
