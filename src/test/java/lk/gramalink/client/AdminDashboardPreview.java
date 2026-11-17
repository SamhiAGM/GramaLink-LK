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

/** Explicit test-classpath launcher only; never used by the real login application. */
public final class AdminDashboardPreview {
    public static void main(String[] args) throws Exception {
        CountDownLatch closed = new CountDownLatch(1); CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            OfflineAdminWorkspace fixture = new OfflineAdminWorkspace();
            try {
                FXMLLoader loader = new FXMLLoader(AdminDashboardView.class.getResource("admin-dashboard.fxml")); BorderPane root = loader.load();
                Label warning = new Label("OFFLINE ADMIN PREVIEW | NOT SIGNED IN | All data and totals are fictional | Administrative submissions are disabled");
                warning.setStyle("-fx-background-color: #ffe2a6; -fx-text-fill: #593900; -fx-font-weight: bold; -fx-padding: 14;");
                warning.setMaxWidth(Double.MAX_VALUE); root.setTop(warning);
                Scene scene = new Scene(root, 1340, 940);
                scene.getStylesheets().add(AdminDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
                scene.getStylesheets().add(AdminDashboardView.class.getResource("admin-dashboard.css").toExternalForm());
                Stage stage = new Stage(); stage.setTitle("GramaLink LK | OFFLINE ADMIN PREVIEW - NOT SIGNED IN"); stage.setMinWidth(1120); stage.setMinHeight(780);
                AdminDashboardController controller = loader.getController(); controller.attach(fixture, stage::close);
                ((Label)root.lookup("#connectionLabel")).setText("Offline preview - no server");
                Circle dot = (Circle)root.lookup("#connectionDot"); dot.getStyleClass().removeAll("online"); dot.getStyleClass().add("offline");
                stage.setScene(scene); stage.setOnHidden(event -> { fixture.close(); closed.countDown(); }); fixture.sampleData();
                Platform.runLater(() -> {
                    ((Label)root.lookup("#overviewAsOf")).setText("Fictional local preview snapshot only. No server was contacted.");
                    ((Label)root.lookup("#feedback")).setText("Offline preview. Explore fictional tables, validate drafts and test navigation. No account, office, statistic or audit record is persisted.");
                });
                stage.show(); System.out.println("ADMIN_OFFLINE_PREVIEW_OPENED: no NetworkClient or login used");
                if (Boolean.getBoolean("gramalink.previewSmoke")) {
                    PauseTransition pause = new PauseTransition(Duration.seconds(1));
                    pause.setOnFinished(event -> {
                        try {
                            TabPane tabs = (TabPane)root.lookup("#sections"); if (tabs.getTabs().size() != 6) throw new AssertionError("Expected six Admin sections");
                            for (int i = 0; i < 6; i++) { tabs.getSelectionModel().select(i); root.applyCss(); root.layout(); saveSnapshot(scene, "target/u05-preview-" + i + ".png"); }
                            tabs.getSelectionModel().select(2); ((ScrollPane)loader.getNamespace().get("usersScroll")).setVvalue(1);
                            root.applyCss(); root.layout(); saveSnapshot(scene, "target/u05-preview-user-form.png");
                            tabs.getSelectionModel().select(3); ((ScrollPane)loader.getNamespace().get("officesScroll")).setVvalue(1);
                            root.applyCss(); root.layout(); saveSnapshot(scene, "target/u05-preview-office-form.png");
                            if (fixture.operations != 0) throw new AssertionError("Offline preview must not submit operations");
                            System.out.println("ADMIN_OFFLINE_PREVIEW_SMOKE_PASS: six sections and management forms rendered; zero submissions"); started.complete(null);
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
