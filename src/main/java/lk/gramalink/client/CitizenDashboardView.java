package lk.gramalink.client;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

/** The sole production navigation entry point; no preview flag or local role selector. */
public final class CitizenDashboardView {
    private CitizenDashboardView() { }
    public static void open(Stage stage, CitizenGateway gateway) throws IOException {
        gateway.requireCitizen();
        FXMLLoader loader = new FXMLLoader(CitizenDashboardView.class.getResource("citizen-dashboard.fxml"));
        Scene dashboard = new Scene(loader.load(), 1180, 800);
        dashboard.getStylesheets().add(CitizenDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
        Scene login = stage.getScene();
        CitizenDashboardController controller = loader.getController();
        controller.attach(gateway, () -> {
            stage.setScene(login);
            stage.setTitle("GramaLink LK | Sign in");
        });
        gateway.requireCitizen();
        stage.setScene(dashboard);
        stage.setTitle("GramaLink LK | Citizen workspace");
    }
}
