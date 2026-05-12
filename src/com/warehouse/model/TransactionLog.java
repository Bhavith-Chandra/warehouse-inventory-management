package com.warehouse.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Audit-log entry capturing every stock-changing operation.
 *
 * The server records one of these every time stock is added, removed,
 * adjusted, or a product is created/deleted. Persisted in the
 * {@code transactions} table and shipped to clients for the
 * "Transaction History" tab.
 */
public class TransactionLog implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Type {
        ADD,            // stock added (replenishment / receiving)
        REMOVE,         // stock removed (sale / shipment)
        ADJUST,         // manual adjustment (cycle count correction)
        CREATE_PRODUCT, // new product introduced to catalogue
        DELETE_PRODUCT  // product removed from catalogue
    }

    private int id;
    private int productId;
    private String productName;
    private Type type;
    private int quantityChange;     // signed: +n for add, -n for remove
    private int resultingQuantity;  // stock level after this transaction
    private String performedBy;     // username of the actor
    private LocalDateTime timestamp;
    private String reason;          // optional free-text justification

    public TransactionLog() {
    }

    public TransactionLog(int id, int productId, String productName, Type type,
                          int quantityChange, int resultingQuantity,
                          String performedBy, LocalDateTime timestamp, String reason) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.type = type;
        this.quantityChange = quantityChange;
        this.resultingQuantity = resultingQuantity;
        this.performedBy = performedBy;
        this.timestamp = timestamp;
        this.reason = reason;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getProductId() { return productId; }
    public void setProductId(int productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public int getQuantityChange() { return quantityChange; }
    public void setQuantityChange(int quantityChange) { this.quantityChange = quantityChange; }

    public int getResultingQuantity() { return resultingQuantity; }
    public void setResultingQuantity(int resultingQuantity) { this.resultingQuantity = resultingQuantity; }

    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + performedBy + " " + type + " "
                + quantityChange + " of '" + productName + "' (now "
                + resultingQuantity + ")";
    }
}
