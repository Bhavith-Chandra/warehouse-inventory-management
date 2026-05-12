package com.warehouse.protocol;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * A single request sent from client to server, serialised as a Java
 * object over an {@link java.io.ObjectOutputStream}.
 *
 * The protocol is intentionally simple: the {@link Type} discriminator
 * tells the server which operation to perform, and the {@code params}
 * map carries the named arguments. Keeping the payload as a generic
 * map (rather than one class per request) lets us evolve the protocol
 * by adding new request types without touching every layer.
 */
public class Request implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Type {
        // Authentication
        LOGIN,

        // Product CRUD
        LIST_PRODUCTS,
        SEARCH_PRODUCTS,
        GET_PRODUCT,
        ADD_PRODUCT,
        UPDATE_PRODUCT,
        REMOVE_PRODUCT,

        // Stock operations (the concurrency-critical path)
        ADD_STOCK,
        REMOVE_STOCK,
        ADJUST_STOCK,

        // Reporting
        LOW_STOCK_REPORT,
        LIST_TRANSACTIONS,

        // Real-time updates
        SUBSCRIBE_UPDATES,

        // Connection lifecycle
        DISCONNECT
    }

    private final Type type;
    private final Map<String, Object> params;
    private final long requestId;

    public Request(Type type) {
        this(type, new HashMap<>());
    }

    public Request(Type type, Map<String, Object> params) {
        this.type = type;
        this.params = params != null ? params : new HashMap<>();
        this.requestId = System.nanoTime();
    }

    public Type getType() { return type; }
    public Map<String, Object> getParams() { return params; }
    public long getRequestId() { return requestId; }

    public Request put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) params.get(key);
    }

    public int getInt(String key) {
        Object v = params.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required param: " + key);
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    public String getString(String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }

    public double getDouble(String key) {
        Object v = params.get(key);
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    @Override
    public String toString() {
        return "Request{" + type + ", params=" + params + "}";
    }
}
