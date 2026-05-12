package com.warehouse.client;

import com.warehouse.model.Product;
import com.warehouse.model.TransactionLog;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard: KPI summary row, two charts, and a recent-activity list.
 */
public class DashboardView {

    private final Label totalProductsValue = new Label("0");
    private final Label totalUnitsValue    = new Label("0");
    private final Label totalValueValue    = new Label("$0");
    private final Label lowStockValue      = new Label("0");
    private final Label lowStockNote       = new Label("All stock healthy");

    private final PieChart categoryChart = new PieChart();
    private final BarChart<String, Number> topStockChart;
    private final XYChart.Series<String, Number> topSeries = new XYChart.Series<>();

    private final VBox activityList = new VBox(0);
    private final VBox root;

    public DashboardView(String username) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis   yAxis = new NumberAxis();
        yAxis.setLabel("Units");
        topStockChart = new BarChart<>(xAxis, yAxis);
        topStockChart.setLegendVisible(false);
        topStockChart.setAnimated(false);
        topStockChart.setTitle("Top 10 Products by Stock Level");
        topStockChart.getData().add(topSeries);
        topStockChart.setPrefHeight(280);

        categoryChart.setLabelsVisible(true);
        categoryChart.setLegendVisible(true);
        categoryChart.setAnimated(false);
        categoryChart.setTitle("Stock by Category");
        categoryChart.setPrefHeight(280);

        // ── Header ──
        Label hello = new Label("Dashboard");
        hello.getStyleClass().add("page-title");
        Label sub = new Label("Overview of current warehouse status.");
        sub.getStyleClass().add("page-subtitle");
        VBox header = new VBox(3, hello, sub);

        // ── KPI row ──
        HBox kpiRow = new HBox(16,
            kpiCard("Total Products",   totalProductsValue, null),
            kpiCard("Units in Stock",   totalUnitsValue,    null),
            kpiCard("Inventory Value",  totalValueValue,    null),
            kpiCard("Low Stock Items",  lowStockValue,      lowStockNote)
        );
        for (Node n : kpiRow.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        // ── Charts row ──
        VBox catCard = chartCard("Stock by Category", categoryChart);
        VBox topCard = chartCard("Top Stock Levels",  topStockChart);
        HBox.setHgrow(catCard, Priority.ALWAYS);
        HBox.setHgrow(topCard, Priority.ALWAYS);
        HBox chartsRow = new HBox(16, catCard, topCard);

        // ── Activity ──
        Label actTitle = new Label("Recent Activity");
        actTitle.getStyleClass().add("section-title");

        ScrollPane sp = new ScrollPane(activityList);
        sp.setFitToWidth(true);
        sp.setPrefHeight(240);
        sp.setStyle("-fx-background-color: transparent;");

        VBox actCard = new VBox(10, actTitle, new Separator(), sp);
        actCard.getStyleClass().add("card");

        // ── Content ──
        VBox content = new VBox(18, header, kpiRow, chartsRow, actCard);
        content.setPadding(new Insets(20));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root = new VBox(scroller);
        VBox.setVgrow(scroller, Priority.ALWAYS);
    }

    public Node getNode() { return root; }

    /* ─── KPI card: just a label and value, no icons or gradients ─── */
    private VBox kpiCard(String label, Label valueLabel, Label noteLabel) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("kpi-label");

        valueLabel.getStyleClass().add("kpi-value");

        VBox card = new VBox(6, lbl, valueLabel);
        if (noteLabel != null) {
            noteLabel.getStyleClass().add("kpi-delta");
            card.getChildren().add(noteLabel);
        }
        card.getStyleClass().add("kpi-card");
        return card;
    }

    private VBox chartCard(String title, Node body) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        VBox box = new VBox(8, t, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        box.getStyleClass().add("card");
        return box;
    }

    /* ─── Refresh ─── */
    public void refresh(List<Product> products, List<TransactionLog> logs) {
        Platform.runLater(() -> {
            int count   = products.size();
            int units   = products.stream().mapToInt(Product::getQuantity).sum();
            double val  = products.stream()
                    .mapToDouble(p -> p.getQuantity() * p.getUnitPrice()).sum();
            long low    = products.stream().filter(Product::isLowStock).count();

            totalProductsValue.setText(String.valueOf(count));
            totalUnitsValue.setText(String.format("%,d", units));
            totalValueValue.setText(String.format("$%,.2f", val));
            lowStockValue.setText(String.valueOf(low));
            lowStockValue.setStyle(low > 0
                ? "-fx-text-fill: #dc2626; -fx-font-size: 28px; -fx-font-weight: bold;"
                : "-fx-font-size: 28px; -fx-font-weight: bold;");
            lowStockNote.setText(low == 0 ? "All stock healthy" : low + " need restocking");

            // Pie chart
            Map<String, Integer> byCat = new LinkedHashMap<>();
            for (Product p : products) {
                String cat = (p.getCategory() == null || p.getCategory().isBlank())
                        ? "Other" : p.getCategory();
                byCat.merge(cat, p.getQuantity(), Integer::sum);
            }
            ObservableList<PieChart.Data> pie = FXCollections.observableArrayList();
            byCat.forEach((cat, qty) -> pie.add(new PieChart.Data(cat, qty)));
            categoryChart.setData(pie);

            // Bar chart
            topSeries.getData().clear();
            products.stream()
                .sorted(Comparator.comparingInt(Product::getQuantity).reversed())
                .limit(10)
                .forEach(p -> topSeries.getData().add(
                    new XYChart.Data<>(abbreviate(p.getName()), p.getQuantity())));

            // Activity feed
            activityList.getChildren().clear();
            if (logs.isEmpty()) {
                Label empty = new Label("No transactions yet.");
                empty.getStyleClass().add("subtle-text");
                empty.setPadding(new Insets(12));
                activityList.getChildren().add(empty);
            } else {
                logs.stream().limit(20).forEach(tx ->
                    activityList.getChildren().add(activityRow(tx)));
            }
        });
    }

    private Node activityRow(TransactionLog tx) {
        String typeStyle = switch (tx.getType()) {
            case ADD            -> "activity-action-add";
            case REMOVE         -> "activity-action-remove";
            case ADJUST         -> "activity-action-adjust";
            case CREATE_PRODUCT -> "activity-action-create";
            case DELETE_PRODUCT -> "activity-action-delete";
        };

        Label user   = new Label(tx.getPerformedBy());
        user.setStyle("-fx-font-weight: bold; -fx-text-fill: #374151;");
        Label action = new Label(tx.getType().name());
        action.getStyleClass().add(typeStyle);
        Label what   = new Label(delta(tx) + " x " + tx.getProductName() +
                " (now " + tx.getResultingQuantity() + ")");
        what.setStyle("-fx-text-fill: #6b7280;");
        Label time   = new Label(relTime(tx.getTimestamp()));
        time.getStyleClass().add("activity-time");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, user, action, what, spacer, time);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("activity-row");
        return row;
    }

    private static String delta(TransactionLog tx) {
        int d = tx.getQuantityChange();
        return d > 0 ? "+" + d : String.valueOf(d);
    }

    private static String abbreviate(String name) {
        return (name != null && name.length() > 14) ? name.substring(0, 13) + "..." : name;
    }

    private static String relTime(LocalDateTime ts) {
        if (ts == null) return "";
        long s = Duration.between(ts, LocalDateTime.now()).getSeconds();
        if (s < 0)     return "just now";
        if (s < 60)    return s + "s ago";
        if (s < 3600)  return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
