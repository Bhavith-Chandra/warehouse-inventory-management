package com.warehouse.protocol;

import java.io.Serializable;

/**
 * Server-to-client response counterpart to {@link Request}.
 *
 * A response carries a {@link Status} flag and either a payload (for
 * successful responses) or a human-readable error message (for failed
 * responses). The {@code requestId} echoes the originating request so
 * clients can correlate replies with calls that were issued
 * asynchronously.
 *
 * The {@link Status#EVENT} status is used for unsolicited push
 * notifications from the server (e.g. another client just changed a
 * product's stock and we want all subscribed clients to refresh).
 */
public class Response implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { OK, ERROR, EVENT }

    private final Status status;
    private final String message;
    private final Object payload;
    private final long requestId;
    private final EventType eventType;

    public enum EventType {
        STOCK_CHANGED,
        PRODUCT_ADDED,
        PRODUCT_REMOVED,
        TRANSACTION_LOGGED
    }

    private Response(Status status, String message, Object payload,
                     long requestId, EventType eventType) {
        this.status = status;
        this.message = message;
        this.payload = payload;
        this.requestId = requestId;
        this.eventType = eventType;
    }

    public static Response ok(Object payload, long requestId) {
        return new Response(Status.OK, null, payload, requestId, null);
    }

    public static Response ok(Object payload) {
        return new Response(Status.OK, null, payload, 0L, null);
    }

    public static Response error(String message, long requestId) {
        return new Response(Status.ERROR, message, null, requestId, null);
    }

    public static Response event(EventType type, Object payload) {
        return new Response(Status.EVENT, null, payload, 0L, type);
    }

    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public Object getPayload() { return payload; }
    public long getRequestId() { return requestId; }
    public EventType getEventType() { return eventType; }

    public boolean isOk() { return status == Status.OK; }
    public boolean isEvent() { return status == Status.EVENT; }

    @SuppressWarnings("unchecked")
    public <T> T payloadAs() {
        return (T) payload;
    }

    @Override
    public String toString() {
        return "Response{" + status + (message != null ? ", msg=" + message : "")
                + ", payload=" + payload + "}";
    }
}
