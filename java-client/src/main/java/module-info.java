module com.example.cheque {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    opens com.example.cheque to javafx.fxml;
    exports com.example.cheque;
}
