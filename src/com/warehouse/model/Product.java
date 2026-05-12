package com.warehouse.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Serializable domain model representing a single warehouse product.
 *
 * Instances of this class travel over the network between the JavaFX
 * client and the server via {@link java.io.ObjectOutputStream}, so the
 * class must remain {@link Serializable} and must keep a stable
 * {@code serialVersionUID} across releases.
 */
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String sku;
    private String name;
    private String category;
    private int quantity;
    private int reorderLevel;
    private double unitPrice;
    private String location;
    private LocalDateTime lastUpdated;

    public Product() {
        // No-arg constructor for deserialization and JavaFX bindings.
    }

    public Product(int id, String sku, String name, String category, int quantity,
                   int reorderLevel, double unitPrice, String location,
                   LocalDateTime lastUpdated) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.quantity = quantity;
        this.reorderLevel = reorderLevel;
        this.unitPrice = unitPrice;
        this.location = location;
        this.lastUpdated = lastUpdated;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public int getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(int reorderLevel) { this.reorderLevel = reorderLevel; }

    public double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public boolean isLowStock() {
        return quantity <= reorderLevel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product)) return false;
        Product other = (Product) o;
        return id == other.id && Objects.equals(sku, other.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sku);
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", sku='" + sku + "', name='" + name
                + "', qty=" + quantity + ", reorder=" + reorderLevel + "}";
    }
}
