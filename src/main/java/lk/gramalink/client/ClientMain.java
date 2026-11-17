package lk.gramalink.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ClientMain extends Application {
    private LoginController controller;

    @Override public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(ClientMain.class.getResource("login.fxml"));
        Scene scene = new Scene(loader.load(), 1000, 720);
        scene.getStylesheets().add(ClientMain.class.getResource("login.css").toExternalForm());
        controller = loader.getController();
        stage.setTitle("GramaLink LK | Sign in");
        stage.setMinWidth(860);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
    }

    @Override public void stop() { if (controller != null) controller.close(); }
    public static void main(String[] args) { launch(args); }
}
