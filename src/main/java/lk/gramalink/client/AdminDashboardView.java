package lk.gramalink.client;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public final class AdminDashboardView {
    private AdminDashboardView() { }
    public static void open(Stage stage, AdminGateway gateway) throws IOException {
        gateway.requireAdministrator();
        FXMLLoader loader = new FXMLLoader(AdminDashboardView.class.getResource("admin-dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1340, 920);
        scene.getStylesheets().add(AdminDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
        scene.getStylesheets().add(AdminDashboardView.class.getResource("admin-dashboard.css").toExternalForm());
        Scene login = stage.getScene();
        AdminDashboardController controller = loader.getController();
        controller.attach(gateway, () -> { stage.setScene(login); stage.setTitle("GramaLink LK | Sign in"); });
        gateway.requireAdministrator(); stage.setScene(scene); stage.setTitle("GramaLink LK | Administrator workspace");
    }
}
