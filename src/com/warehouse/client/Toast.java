package com.warehouse.client;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Lightweight, non-blocking toast notifications.
 *
 * <p>Designed as a drop-in replacement for {@code Alert.INFORMATION}
 * for success / info messages so the user is not constantly clicking
 * "OK". Toasts slide up from the bottom-right of the host window,
 * pause for the configured duration, then fade out and remove
 * themselves.
 *
 * <p>The container is a transparent {@link VBox} pinned to the
 * bottom-right of a {@link StackPane}; the caller is expected to set
 * up that {@link StackPane} once at application start by calling
 * {@link #install(StackPane)}.
 */
public final class Toast {

    public enum Kind { INFO, SUCCESS, WARN, ERROR }

    private static VBox container;

    private Toast() { }

    public static void install(StackPane root) {
        container = new VBox(8);
        container.setPickOnBounds(false); // clicks pass through to UI below
        container.setMouseTransparent(true);
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setPadding(new Insets(0, 24, 24, 0));
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        root.getChildren().add(container);
    }

    public static void info(String msg)    { show(msg, Kind.INFO); }
    public static void success(String msg) { show(msg, Kind.SUCCESS); }
    public static void warn(String msg)    { show(msg, Kind.WARN); }
    public static void error(String msg)   { show(msg, Kind.ERROR); }

    public static void show(String msg, Kind kind) {
        if (container == null) {
            // Fallback for early-startup toasts before install() has run.
            System.err.println("[toast " + kind + "] " + msg);
            return;
        }
        Platform.runLater(() -> {
            Label label = new Label(prefix(kind) + "  " + msg);
            label.getStyleClass().addAll("toast", styleClass(kind));
            label.setMaxWidth(420);
            label.setWrapText(true);

            container.getChildren().add(label);

            // Slide up + fade in, pause, fade out, remove.
            label.setOpacity(0);
            label.setTranslateY(20);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(180), label);
            fadeIn.setFromValue(0); fadeIn.setToValue(1);

            TranslateTransition slide = new TranslateTransition(Duration.millis(220), label);
            slide.setFromY(20); slide.setToY(0);

            PauseTransition hold = new PauseTransition(Duration.seconds(durationFor(kind)));

            FadeTransition fadeOut = new FadeTransition(Duration.millis(280), label);
            fadeOut.setFromValue(1); fadeOut.setToValue(0);

            SequentialTransition seq = new SequentialTransition(fadeIn, hold, fadeOut);
            seq.setOnFinished(e -> container.getChildren().remove(label));

            slide.play();
            seq.play();

            // Don't let the toast stack grow unbounded.
            while (container.getChildren().size() > 4) {
                container.getChildren().remove(0);
            }
        });
    }

    private static String prefix(Kind k) {
        return switch (k) {
            case SUCCESS -> "[OK]";
            case WARN    -> "[!]";
            case ERROR   -> "[X]";
            case INFO    -> "[i]";
        };
    }

    private static String styleClass(Kind k) {
        return switch (k) {
            case SUCCESS -> "success";
            case WARN    -> "warn";
            case ERROR   -> "error";
            case INFO    -> "";
        };
    }

    private static int durationFor(Kind k) {
        return switch (k) {
            case ERROR -> 6;
            case WARN  -> 5;
            default    -> 3;
        };
    }
}
