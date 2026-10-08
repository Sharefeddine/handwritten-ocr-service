package com.example.cheque;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ChequeApp extends Application {
    @Override public void start(Stage stage) throws Exception {
        stage.setTitle("Cheque OCR review");
        stage.setScene(new Scene(FXMLLoader.load(getClass().getResource("review.fxml")), 1200, 760));
        stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
