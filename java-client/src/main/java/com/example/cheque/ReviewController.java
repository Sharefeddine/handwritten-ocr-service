package com.example.cheque;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;

public class ReviewController {
    @FXML private TextField url, correction;
    @FXML private TextArea rawText, allText;
    @FXML private Label status;
    @FXML private Button scanButton;
    @FXML private ComboBox<String> bankSelector;
    @FXML private ImageView imageView;
    @FXML private Pane imagePane;
    @FXML private TableView<Field> fields;
    @FXML private TableColumn<Field, String> nameColumn, rawColumn, correctedColumn, confidenceColumn, stateColumn;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> profiles = new LinkedHashMap<>();
    private Path imagePath;
    private JsonNode original;
    private Rectangle highlight;
    private static final List<String> FIELD_NAMES = List.of("bank", "reference", "amountDigits", "amountWords", "beneficiary", "date", "drawer");

    public static class Field {
        public String name, raw, corrected, state, reason;
        public double confidence;
        public JsonNode bbox;
        Field(String name, String raw, double confidence, JsonNode bbox, String corrected, String state, String reason) {
            this.name = name; this.raw = raw; this.confidence = confidence; this.bbox = bbox;
            this.corrected = corrected; this.state = state; this.reason = reason;
        }
    }

    @FXML private void initialize() throws IOException {
        try (var stream = getClass().getResourceAsStream("/profiles/BNA.json")) {
            JsonNode profile = json.readTree(stream);
            profiles.put(profile.path("bankId").asText(), profile);
        }
        bankSelector.setItems(FXCollections.observableArrayList(profiles.keySet()));
        bankSelector.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (original != null) populate();
        });
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        rawColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().raw));
        correctedColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().corrected));
        confidenceColumn.setCellValueFactory(c -> new SimpleStringProperty(String.format(Locale.ROOT, "%.2f", c.getValue().confidence)));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state));
        fields.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> select(value));
    }

    @FXML private void openImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select cheque image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp"));
        var file = chooser.showOpenDialog(imageView.getScene().getWindow());
        if (file == null) return;
        imagePath = file.toPath();
        imageView.setImage(new Image(file.toURI().toString()));
        imagePane.setPrefSize(570, imageView.getImage().getHeight() * 570 / imageView.getImage().getWidth());
        original = null;
        fields.getItems().clear();
        allText.clear();
        clearHighlight();
        status.setText("Selected " + file.getName() + ". Run OCR to extract lines.");
    }

    @FXML private void scan() {
        if (imagePath == null) { status.setText("Select an image first."); return; }
        scanButton.setDisable(true);
        status.setText("Running OCR (first request may download models)...");
        Thread worker = new Thread(() -> {
            try {
                String body = json.writeValueAsString(Map.of("image", Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath))));
                HttpRequest request = HttpRequest.newBuilder(URI.create(url.getText().trim()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("OCR HTTP " + response.statusCode() + ": " + response.body());
                JsonNode result = json.readTree(response.body());
                Platform.runLater(() -> {
                    original = result;
                    allText.setText(result.path("text").asText());
                    if (bankSelector.getValue() == null) detectBank();
                    populate();
                    status.setText("Raw OCR loaded. Review every financial field before saving.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText("OCR failed: " + ex.getMessage()));
            } finally {
                Platform.runLater(() -> scanButton.setDisable(false));
            }
        }, "ocr-request");
        worker.setDaemon(true);
        worker.start();
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s.toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("[^A-Z0-9]", "");
    }

    private void detectBank() {
        String text = normalize(original.path("text").asText());
        for (var entry : profiles.entrySet()) {
            for (JsonNode alias : entry.getValue().path("aliases")) {
                if (text.contains(normalize(alias.asText()))) {
                    bankSelector.setValue(entry.getKey());
                    return;
                }
            }
        }
        status.setText("Bank not detected. Choose a bank profile manually.");
    }

    private void populate() {
        JsonNode profile = profiles.get(bankSelector.getValue());
        if (profile == null || original == null) { fields.getItems().clear(); return; }
        var rows = FXCollections.<Field>observableArrayList();
        Image image = imageView.getImage();
        for (String name : FIELD_NAMES) {
            JsonNode zone = profile.path("zones").path(name);
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode line : original.path("lines")) {
                JsonNode box = line.path("bbox");
                if (box.size() != 4 || zone.size() != 4) continue;
                double x = (box.get(0).asDouble() + box.get(2).asDouble()) / (2 * image.getWidth());
                double y = (box.get(1).asDouble() + box.get(3).asDouble()) / (2 * image.getHeight());
                if (x >= zone.get(0).asDouble() && x <= zone.get(0).asDouble() + zone.get(2).asDouble()
                    && y >= zone.get(1).asDouble() && y <= zone.get(1).asDouble() + zone.get(3).asDouble()) matches.add(line);
            }
            matches.sort(Comparator.comparingDouble((JsonNode line) -> line.path("bbox").get(1).asDouble())
                .thenComparingDouble(line -> line.path("bbox").get(0).asDouble()));
            String raw = String.join(" ", matches.stream().map(line -> line.path("text").asText()).toList());
            double confidence = matches.stream().mapToDouble(line -> line.path("confidence").asDouble()).min().orElse(0);
            JsonNode bbox = matches.isEmpty() ? null : matches.get(0).path("bbox");
            String suggested = suggest(name, raw, profile);
            double threshold = profile.path("minimumConfidence").path(name).asDouble(1);
            String state = raw.isBlank() || suggested == null || confidence < threshold ? "Review" : "Valid";
            rows.add(new Field(name, raw, confidence, bbox, suggested == null ? raw : suggested, state,
                suggested != null && !suggested.equals(raw) ? "PROFILE_NORMALIZATION" : "UNCHANGED"));
        }
        fields.setItems(rows);
    }

    private String suggest(String name, String raw, JsonNode profile) {
        if (raw.isBlank()) return null;
        if (name.equals("bank")) return profile.path("bankId").asText();
        if (name.equals("reference")) {
            String value = raw.toUpperCase(Locale.ROOT).replace('O', '0').replace('I', '1').replace('L', '1').replaceAll("[^0-9]", "");
            for (JsonNode length : profile.path("referenceLengths")) if (value.length() == length.asInt()) return value;
            return null;
        }
        if (name.equals("amountDigits")) {
            String value = raw.toUpperCase(Locale.ROOT).replaceAll("D\\.?A|DZD", "").replace('O', '0').replace('I', '1').replace('L', '1').trim();
            if (!Pattern.matches("(?:[0-9]{1,3}(?:[ .][0-9]{3})+|[0-9]+)(?:,[0-9]{1,2})?", value)) return null;
            try { return new java.math.BigDecimal(value.replace(" ", "").replace(".", "").replace(',', '.')).setScale(2).toPlainString(); }
            catch (NumberFormatException ex) { return null; }
        }
        if (name.equals("date")) {
            String value = raw.toUpperCase(Locale.ROOT).replace('O', '0').replace('I', '1').replace('L', '1');
            var matcher = Pattern.compile("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}\\b").matcher(value);
            if (matcher.find()) {
                String candidate = matcher.group().replace('-', '/').replace('.', '/');
                try { return LocalDate.parse(candidate, DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)).toString(); }
                catch (Exception ignored) { return null; }
            }
            return null;
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private void clearHighlight() { if (highlight != null) imagePane.getChildren().remove(highlight); highlight = null; }
    private void select(Field field) {
        clearHighlight();
        if (field == null) { rawText.clear(); correction.clear(); return; }
        rawText.setText(field.raw);
        correction.setText(field.corrected);
        if (field.bbox == null || field.bbox.size() != 4) return;
        double scale = imageView.getFitWidth() / imageView.getImage().getWidth();
        highlight = new Rectangle(field.bbox.get(0).asDouble() * scale, field.bbox.get(1).asDouble() * scale,
            (field.bbox.get(2).asDouble() - field.bbox.get(0).asDouble()) * scale,
            (field.bbox.get(3).asDouble() - field.bbox.get(1).asDouble()) * scale);
        highlight.setFill(Color.color(1, 0.7, 0, 0.25)); highlight.setStroke(Color.ORANGE); highlight.setMouseTransparent(true);
        imagePane.getChildren().add(highlight);
    }

    @FXML private void accept() {
        Field field = fields.getSelectionModel().getSelectedItem();
        if (field == null) return;
        String value = correction.getText().trim();
        if (value.isEmpty()) { status.setText("Corrected value cannot be empty."); return; }
        field.reason = value.equals(field.raw) ? "USER_CONFIRMED" : "MANUAL_CORRECTION";
        field.corrected = value;
        field.state = "Accepted";
        fields.refresh();
        int next = fields.getSelectionModel().getSelectedIndex() + 1;
        if (next < fields.getItems().size()) fields.getSelectionModel().select(next);
    }

    @FXML private void acceptValid() {
        for (Field field : fields.getItems()) if (field.state.equals("Valid")) {
            field.state = "Accepted";
            field.reason = field.corrected.equals(field.raw) ? "USER_CONFIRMED" : "PROFILE_NORMALIZATION";
        }
        fields.refresh();
    }

    @FXML private void save() {
        if (imagePath == null || original == null) { status.setText("Run OCR first."); return; }
        if (fields.getItems().stream().anyMatch(field -> !field.state.equals("Accepted"))) {
            status.setText("Accept or correct every field before saving."); return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(imagePath.getFileName().toString() + ".review.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showSaveDialog(imageView.getScene().getWindow());
        if (file == null) return;
        try {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("bankId", bankSelector.getValue());
            review.put("rawOcr", original);
            review.put("fields", fields.getItems().stream().map(f -> Map.of(
                "name", f.name, "raw", f.raw, "corrected", f.corrected,
                "confidence", f.confidence, "reason", f.reason, "status", f.state)).toList());
            json.writerWithDefaultPrettyPrinter().writeValue(file, review);
            status.setText("Saved review: " + file);
        } catch (IOException ex) { status.setText("Save failed: " + ex.getMessage()); }
    }
}
