package lk.gramalink.client;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.concurrent.*;

/** Opt-in test-classpath launcher. Production ClientMain has no reference to this class. */
public final class CitizenDashboardPreview {
    public static void main(String[] args) throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            OfflineCitizenWorkspace fixture = new OfflineCitizenWorkspace();
            try {
                FXMLLoader loader = new FXMLLoader(CitizenDashboardView.class.getResource("citizen-dashboard.fxml"));
                BorderPane root = loader.load();
                Label warning = new Label("OFFLINE UI PREVIEW  |  Not signed in  |  Fictional samples only  |  Nothing is sent or persisted");
                warning.setStyle("-fx-background-color: #ffe2a6; -fx-text-fill: #593900; -fx-font-weight: bold; -fx-padding: 14;");
                warning.setMaxWidth(Double.MAX_VALUE);
                root.setTop(warning);
                Scene scene = new Scene(root, 1180, 860);
                scene.getStylesheets().add(CitizenDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
                Stage stage = new Stage();
                stage.setTitle("GramaLink LK | OFFLINE UI PREVIEW — NOT SIGNED IN");
                stage.setMinWidth(1000); stage.setMinHeight(760);
                CitizenDashboardController controller = loader.getController();
                controller.attach(fixture, stage::close);
                ((Label)root.lookup("#connectionLabel")).setText("Offline preview — no server");
                Circle dot = (Circle)root.lookup("#connectionDot");
                dot.getStyleClass().removeAll("online"); dot.getStyleClass().add("offline");
                stage.setScene(scene);
                stage.setOnHidden(event -> { fixture.close(); closed.countDown(); });
                fixture.sampleData();
                Platform.runLater(() -> {
                    ((Label)root.lookup("#requestCountCaption")).setText("Local preview samples");
                    ((Label)root.lookup("#notificationCountCaption")).setText("Local preview samples");
                    ((Label)root.lookup("#feedback")).setText("Offline preview. Check form validation and navigation; submission, messaging and uploads are disabled.");
                    ((Label)root.lookup("#overviewHint")).setText("Explore the fictional request samples in My Requests. No server was contacted.");
                });
                stage.show();
                System.out.println("OFFLINE_PREVIEW_OPENED: no NetworkClient or login used");
                if (Boolean.getBoolean("gramalink.previewSmoke")) {
                    PauseTransition pause = new PauseTransition(Duration.seconds(1));
                    pause.setOnFinished(event -> {
                        try {
                            TabPane tabs = (TabPane)root.lookup("#sections");
                            if (tabs.getTabs().size() != 7) throw new AssertionError("Expected seven dashboard sections");
                            for (int i = 0; i < tabs.getTabs().size(); i++) {
                                tabs.getSelectionModel().select(i); root.applyCss(); root.layout();
                                saveSnapshot(scene, i);
                            }
                            System.out.println("OFFLINE_PREVIEW_SMOKE_PASS: all seven sections rendered");
                            started.complete(null);
                        } catch (Throwable error) { started.completeExceptionally(error); }
                        finally { stage.close(); }
                    });
                    pause.play();
                } else started.complete(null);
            } catch (Throwable error) { fixture.close(); started.completeExceptionally(error); closed.countDown(); }
        });
        try { started.get(30, TimeUnit.SECONDS); closed.await(); }
        finally { Platform.exit(); }
    }

    private static void saveSnapshot(Scene scene, int section) throws java.io.IOException {
        var snapshot = scene.snapshot(null);
        var image = new java.awt.image.BufferedImage((int)snapshot.getWidth(), (int)snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(image, "png", new java.io.File("target/u02-preview-" + section + ".png"));
    }
}
