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

/** Opt-in test-classpath launcher. Never used by ClientMain or production authorization. */
public final class DsDashboardPreview {
    public static void main(String[] args) throws Exception {
        CountDownLatch closed = new CountDownLatch(1); CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            OfflineDsWorkspace fixture = new OfflineDsWorkspace();
            try {
                FXMLLoader loader = new FXMLLoader(DsDashboardView.class.getResource("ds-dashboard.fxml")); BorderPane root = loader.load();
                Label warning = new Label("OFFLINE DS PREVIEW | NOT SIGNED IN | Fictional data only | No approvals, rejections, returns, messages or uploads");
                warning.setStyle("-fx-background-color: #ffe2a6; -fx-text-fill: #593900; -fx-font-weight: bold; -fx-padding: 14;");
                warning.setMaxWidth(Double.MAX_VALUE); root.setTop(warning);
                Scene scene = new Scene(root, 1320, 940);
                scene.getStylesheets().add(DsDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
                scene.getStylesheets().add(DsDashboardView.class.getResource("ds-dashboard.css").toExternalForm());
                Stage stage = new Stage(); stage.setTitle("GramaLink LK | OFFLINE DS PREVIEW - NOT SIGNED IN"); stage.setMinWidth(1100); stage.setMinHeight(780);
                DsDashboardController controller = loader.getController(); controller.attach(fixture, stage::close);
                ((Label)root.lookup("#connectionLabel")).setText("Offline preview - no server");
                Circle dot = (Circle)root.lookup("#connectionDot"); dot.getStyleClass().removeAll("online"); dot.getStyleClass().add("offline");
                stage.setScene(scene); stage.setOnHidden(event -> { fixture.close(); closed.countDown(); }); fixture.sampleData();
                Platform.runLater(() -> {
                    ((Label)root.lookup("#forwardedCaption")).setText("Fictional local preview samples");
                    ((Label)root.lookup("#notificationCaption")).setText("Fictional local preview samples");
                    ((Label)root.lookup("#feedback")).setText("Offline preview. All data and revisions are fictional. Decision, messaging and upload submissions are disabled.");
                    @SuppressWarnings("unchecked") TableView<DsData.RequestRow> table = (TableView<DsData.RequestRow>)loader.getNamespace().get("forwardedRequests"); table.getSelectionModel().selectFirst();
                });
                stage.show(); System.out.println("DS_OFFLINE_PREVIEW_OPENED: no NetworkClient or login used");
                if (Boolean.getBoolean("gramalink.previewSmoke")) {
                    PauseTransition pause = new PauseTransition(Duration.seconds(1));
                    pause.setOnFinished(event -> {
                        try {
                            TabPane tabs = (TabPane)root.lookup("#sections"); if (tabs.getTabs().size() != 4) throw new AssertionError("Expected four DS sections");
                            for (int i = 0; i < 4; i++) { tabs.getSelectionModel().select(i); root.applyCss(); root.layout(); saveSnapshot(scene, "target/u04-preview-" + i + ".png"); }
                            tabs.getSelectionModel().select(1); ((ScrollPane)loader.getNamespace().get("requestsScroll")).setVvalue(1);
                            root.applyCss(); root.layout(); saveSnapshot(scene, "target/u04-preview-decisions.png");
                            System.out.println("DS_OFFLINE_PREVIEW_SMOKE_PASS: all four sections and decision controls rendered"); started.complete(null);
                        } catch (Throwable error) { started.completeExceptionally(error); } finally { stage.close(); }
                    }); pause.play();
                } else started.complete(null);
            } catch (Throwable error) { fixture.close(); started.completeExceptionally(error); closed.countDown(); }
        });
        try { started.get(30, TimeUnit.SECONDS); closed.await(); } finally { Platform.exit(); }
    }
    private static void saveSnapshot(Scene scene, String file) throws java.io.IOException {
        var snapshot = scene.snapshot(null); var image = new java.awt.image.BufferedImage((int)snapshot.getWidth(), (int)snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(image, "png", new java.io.File(file));
    }
}
