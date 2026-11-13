package lk.gramalink.client;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/** Owns controls only; LoginService owns background connection and request work. */
public final class LoginController implements AutoCloseable {
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Button disconnectButton;
    @FXML private Label statusLabel;
    @FXML private Circle statusDot;
    @FXML private Label feedbackLabel;
    @FXML private VBox inputFields;
    private LoginService service;
    private boolean closed;

    @FXML private void initialize() {
        hostField.setText(NetworkClient.DEFAULT_HOST);
        portField.setText(Integer.toString(NetworkClient.DEFAULT_PORT));
        service = new LoginService(this::updateStatus, Platform::runLater);
    }

    @FXML private void login() {
        final LoginService.Input input;
        try {
            int port;
            try { port = Integer.parseInt(portField.getText().strip()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Enter a numeric port between 1 and 65535."); }
            input = new LoginService.Input(hostField.getText(), port, usernameField.getText(), passwordField.getText());
        } catch (IllegalArgumentException error) { feedbackLabel.setText(error.getMessage()); return; }
        busy(true);
        feedbackLabel.setText("Connecting to " + input.host() + ":" + input.port() + "…");
        passwordField.clear();
        service.login(input).whenComplete((reply, error) -> Platform.runLater(() -> {
            if (closed) return;
            busy(false);
            if (error == null) {
                feedbackLabel.setText(LoginService.describe(reply));
                int authorizedWorkspaces = (service.citizenGateway().isPresent() ? 1 : 0)
                        + (service.gnGateway().isPresent() ? 1 : 0) + (service.dsGateway().isPresent() ? 1 : 0);
                if (authorizedWorkspaces > 1) {
                    feedbackLabel.setText("The server returned ambiguous workspace permissions. Please contact your administrator.");
                    return;
                }
                service.citizenGateway().ifPresent(gateway -> {
                    try { CitizenDashboardView.open((Stage) loginButton.getScene().getWindow(), gateway); }
                    catch (java.io.IOException | SecurityException failure) {
                        feedbackLabel.setText("Unable to open the Citizen workspace. Please sign in again.");
                    }
                });
                service.gnGateway().ifPresent(gateway -> {
                    try { GnDashboardView.open((Stage) loginButton.getScene().getWindow(), gateway); }
                    catch (java.io.IOException | SecurityException failure) {
                        feedbackLabel.setText("Unable to open the GN Officer workspace. Please sign in again.");
                    }
                });
                service.dsGateway().ifPresent(gateway -> {
                    try { DsDashboardView.open((Stage) loginButton.getScene().getWindow(), gateway); }
                    catch (java.io.IOException | SecurityException failure) {
                        feedbackLabel.setText("Unable to open the DS Officer workspace. Please sign in again.");
                    }
                });
            }
            else {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                feedbackLabel.setText(cause instanceof TimeoutException
                        ? "The server did not reply in time. Try again."
                        : "Unable to complete login. Check the server address, port, and server availability.");
            }
        }));
    }

    @FXML private void disconnect() {
        busy(true);
        service.disconnect().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (closed) return;
            busy(false);
            feedbackLabel.setText(error == null ? "Disconnected from the server." : "Unable to disconnect cleanly.");
        }));
    }

    private void busy(boolean busy) {
        inputFields.setDisable(busy);
        loginButton.setDisable(busy);
        disconnectButton.setDisable(busy);
        loginButton.setText(busy ? "Please wait…" : "Connect & log in");
    }

    private void updateStatus(NetworkClient.Status status) {
        if (closed) return;
        boolean connected = status == NetworkClient.Status.CONNECTED;
        statusLabel.setText(connected ? "Connected to server" : "Disconnected");
        statusDot.getStyleClass().removeAll("online", "offline");
        statusDot.getStyleClass().add(connected ? "online" : "offline");
    }

    @Override public void close() { closed = true; service.close(); }
}
