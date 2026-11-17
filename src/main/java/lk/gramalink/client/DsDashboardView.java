package lk.gramalink.client;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public final class DsDashboardView {
    private DsDashboardView() { }
    public static void open(Stage stage, DsGateway gateway) throws IOException {
        gateway.requireDsOfficer();
        FXMLLoader loader = new FXMLLoader(DsDashboardView.class.getResource("ds-dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1320, 920);
        scene.getStylesheets().add(DsDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
        scene.getStylesheets().add(DsDashboardView.class.getResource("ds-dashboard.css").toExternalForm());
        Scene login = stage.getScene();
        DsDashboardController controller = loader.getController();
        controller.attach(gateway, () -> { stage.setScene(login); stage.setTitle("GramaLink LK | Sign in"); });
        gateway.requireDsOfficer(); stage.setScene(scene); stage.setTitle("GramaLink LK | DS Officer workspace");
    }
}
