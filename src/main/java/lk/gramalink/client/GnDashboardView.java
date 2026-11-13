package lk.gramalink.client;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public final class GnDashboardView {
    private GnDashboardView() { }
    public static void open(Stage stage, GnGateway gateway) throws IOException {
        gateway.requireGnOfficer();
        FXMLLoader loader = new FXMLLoader(GnDashboardView.class.getResource("gn-dashboard.fxml"));
        Scene dashboard = new Scene(loader.load(), 1280, 900);
        dashboard.getStylesheets().add(GnDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
        dashboard.getStylesheets().add(GnDashboardView.class.getResource("gn-dashboard.css").toExternalForm());
        Scene login = stage.getScene();
        GnDashboardController controller = loader.getController();
        controller.attach(gateway, () -> { stage.setScene(login); stage.setTitle("GramaLink LK | Sign in"); });
        gateway.requireGnOfficer();
        stage.setScene(dashboard);
        stage.setTitle("GramaLink LK | GN Officer workspace");
    }
}
