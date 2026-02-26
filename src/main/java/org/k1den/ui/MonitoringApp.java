package org.k1den.ui;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.k1den.llm.LlmService;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MonitoringApp extends Application {

    private final ClickHouseRepository repository = new ClickHouseRepository();
    private final LlmService llmService = new LlmService();
    private final String DEVICE_ID = "device-001";

    private final List<MetricConfig> activeMetrics = new ArrayList<>();

    private final Map<String, Label> statusLabels = new HashMap<>();
    private final Map<String, Label> reasonLabels = new HashMap<>();

    private final Map<String, XYChart.Series<Number, Number>> historySeriesMap = new HashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> forecastSeriesMap = new HashMap<>();
    private final Map<String, LineChart<Number, Number>> chartsMap = new HashMap<>();

    // Хранилище активных прогнозов, чтобы они не исчезали при обновлении графика
    private final Map<String, ForecastRecord> activeForecasts = new HashMap<>();

    private Label globalStatusLabel;
    private TabPane tabPane;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        initMetrics();
        primaryStage.setTitle("AI System Monitor: " + DEVICE_ID);

        // --- ВЕРХНЯЯ ПАНЕЛЬ ---
        HBox header = new HBox(15);
        header.setPadding(new Insets(15));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        ComboBox<String> timeBox = new ComboBox<>();
        timeBox.getItems().addAll("5 минут", "15 минут", "30 минут");
        timeBox.getSelectionModel().select(1);

        Button btnPredict = new Button("ЗАПУСТИТЬ ПРОГНОЗ");
        btnPredict.setStyle("-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-weight: bold;");

        Button btnExportPdf = new Button("📄 ЭКСПОРТ В PDF");
        btnExportPdf.setStyle("-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-weight: bold;");

        globalStatusLabel = new Label("ОЖИДАНИЕ...");
        globalStatusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        globalStatusLabel.setTextFill(Color.GRAY);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(new Label("Горизонт:"), timeBox, btnPredict, btnExportPdf, spacer, globalStatusLabel);

        // --- ГРАФИКИ И КАРТОЧКИ ---
        tabPane = new TabPane();
        for (MetricConfig cfg : activeMetrics) {
            tabPane.getTabs().add(createChartTab(cfg));
        }

        ScrollPane scrollPane = new ScrollPane();
        FlowPane cardsPanel = new FlowPane();
        cardsPanel.setPadding(new Insets(20));
        cardsPanel.setHgap(15);
        cardsPanel.setVgap(15);
        cardsPanel.setStyle("-fx-background-color: white;");

        for (MetricConfig cfg : activeMetrics) {
            cardsPanel.getChildren().add(createStatusCard(cfg));
        }
        scrollPane.setContent(cardsPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(280);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(tabPane);
        root.setBottom(scrollPane);

        btnPredict.setOnAction(e -> runFullAnalysis(timeBox.getValue()));
        btnExportPdf.setOnAction(e -> exportActiveTabToPdf(primaryStage, timeBox.getValue()));

        primaryStage.setScene(new Scene(root, 1200, 900));
        primaryStage.show();
    }

    // --- ОБНОВЛЕНИЕ ДАННЫХ НА ГРАФИКЕ ---
    private void refreshChartData(MetricConfig cfg) {
        XYChart.Series<Number, Number> histSeries = historySeriesMap.get(cfg.dbKey);
        XYChart.Series<Number, Number> foreSeries = forecastSeriesMap.get(cfg.dbKey);
        if (histSeries == null || foreSeries == null) return;

        histSeries.getData().clear();
        foreSeries.getData().clear();

        List<MetricPoint> data = repository.getMetricsForLastMinutes(DEVICE_ID, cfg.dbKey, 30);
        for(MetricPoint p : data) {
            histSeries.getData().add(new XYChart.Data<>(p.timestamp, p.value));
        }

        ForecastRecord fr = activeForecasts.get(cfg.dbKey);
        if (fr != null && !data.isEmpty()) {
            for (MetricPoint fp : fr.points) {
                foreSeries.getData().add(new XYChart.Data<>(fp.timestamp, fp.value));
            }
        }
    }

    // --- ЛОГИКА АНАЛИЗА ---
    private void runFullAnalysis(String timeStr) {
        int minutes = Integer.parseInt(timeStr.split(" ")[0]);
        globalStatusLabel.setText("СБОР И АНАЛИЗ AI... ⏳");
        globalStatusLabel.setTextFill(Color.BLUE);

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (MetricConfig cfg : activeMetrics) {
            Platform.runLater(() -> {
                if(statusLabels.containsKey(cfg.dbKey)) {
                    statusLabels.get(cfg.dbKey).setText("Анализ...");
                    statusLabels.get(cfg.dbKey).setStyle("-fx-background-color: #eee; -fx-text-fill: black;");
                }
            });

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                List<MetricPoint> data = repository.getMetricsForLastMinutes(DEVICE_ID, cfg.dbKey, 30);
                List<Double> values = new ArrayList<>();
                for(MetricPoint p : data) values.add(p.value);

                if (values.isEmpty()) return;

                // Запрос в LLM (вернутся 5 точек)
                LlmService.LlmPrediction res = llmService.predict(values, minutes, cfg.title, cfg.rules);

                // --- ФОРМИРУЕМ ТРАЕКТОРИЮ ВРЕМЕНИ ---
                MetricPoint lastRealPoint = data.get(data.size() - 1);
                long startTime = lastRealPoint.timestamp;
                long totalDurationMs = minutes * 60 * 1000L;

                List<MetricPoint> forecastPoints = new ArrayList<>();
                forecastPoints.add(new MetricPoint(startTime, lastRealPoint.value));

                int numPoints = res.predictedValues.size();
                long stepMs = totalDurationMs / Math.max(1, numPoints);

                for (int i = 0; i < numPoints; i++) {
                    long pointTime = startTime + stepMs * (i + 1);
                    forecastPoints.add(new MetricPoint(pointTime, res.predictedValues.get(i)));
                }

                activeForecasts.put(cfg.dbKey, new ForecastRecord(forecastPoints));

                Platform.runLater(() -> {
                    double finalTargetValue = res.predictedValues.get(res.predictedValues.size() - 1);
                    updateCardUI(cfg, finalTargetValue, res.status, res.reason);

                    refreshChartData(cfg); // Перерисовываем
                });
            });
            tasks.add(task);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenRun(() -> {
            Platform.runLater(this::updateGlobalStatus);
        });
    }

    // --- СОЗДАНИЕ ГРАФИКА ---
    private Tab createChartTab(MetricConfig cfg) {
        // Ось X (Время)
        NumberAxis xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new StringConverter<Number>() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            @Override
            public String toString(Number object) { return sdf.format(new Date(object.longValue())); }
            @Override
            public Number fromString(String string) { return 0; }
        });

        // Ось Y (Значения)
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(cfg.unit);

        if (cfg.unit.equals("%")) {
            // Для CPU, RAM и Дисков жестко фиксируем от 0 до 100%
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(0);
            yAxis.setUpperBound(100);
            yAxis.setTickUnit(10);
        } else if (cfg.unit.equals("°C")) {
            // Температуру логично показывать от 20 до 100 градусов
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(20);
            yAxis.setUpperBound(100);
            yAxis.setTickUnit(10);
        } else {
            // Для сети (байты) и процессов разрешаем графику растягиваться самому
            yAxis.setForceZeroInRange(false);
            yAxis.setTickLabelFormatter(new StringConverter<Number>() {
                @Override
                public String toString(Number object) { return String.format("%.0f", object.doubleValue()); }
                @Override
                public Number fromString(String string) { return 0; }
            });
        }

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(cfg.title);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);

        XYChart.Series<Number, Number> historySeries = new XYChart.Series<>();
        historySeries.setName("История");

        XYChart.Series<Number, Number> forecastSeries = new XYChart.Series<>();
        forecastSeries.setName("AI Прогноз");

        chart.getData().addAll(historySeries, forecastSeries);

        historySeriesMap.put(cfg.dbKey, historySeries);
        forecastSeriesMap.put(cfg.dbKey, forecastSeries);
        chartsMap.put(cfg.dbKey, chart);

        Button btnRefresh = new Button("Обновить историю");
        btnRefresh.setOnAction(e -> refreshChartData(cfg));
        btnRefresh.fire();

        VBox box = new VBox(10, btnRefresh, chart);
        box.setPadding(new Insets(10));
        return new Tab(cfg.title, box);
    }

    // --- ОСТАЛЬНЫЕ МЕТОДЫ (Экспорт PDF, Карточки, Инициализация - без изменений) ---

    private void exportActiveTabToPdf(Stage stage, String timeHorizon) {
        int activeTabIndex = tabPane.getSelectionModel().getSelectedIndex();
        if (activeTabIndex < 0 || activeTabIndex >= activeMetrics.size()) return;

        MetricConfig cfg = activeMetrics.get(activeTabIndex);
        LineChart<Number, Number> chart = chartsMap.get(cfg.dbKey);
        Label lblStatus = statusLabels.get(cfg.dbKey);
        Label lblReason = reasonLabels.get(cfg.dbKey);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Сохранить PDF отчет");
        fileChooser.setInitialFileName("AI_Report_" + cfg.dbKey.replace(":", "_") + ".pdf");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));

        File file = fileChooser.showSaveDialog(stage);
        if (file == null) return;

        try {
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, new FileOutputStream(file));
            document.open();

            com.itextpdf.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, BaseColor.DARK_GRAY);
            com.itextpdf.text.Font subFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BaseColor.BLACK);
            com.itextpdf.text.Font textFont = FontFactory.getFont(FontFactory.HELVETICA, 12, BaseColor.BLACK);

            document.add(new Paragraph("System Telemetry & AI Forecast Report", titleFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Device ID: " + DEVICE_ID, subFont));
            document.add(new Paragraph("Metric Analyzed: " + cfg.title, textFont));
            document.add(new Paragraph("Forecast Horizon: " + timeHorizon, textFont));
            document.add(new Paragraph("Generation Date: " + new Date().toString(), textFont));
            document.add(Chunk.NEWLINE);

            WritableImage fxImage = chart.snapshot(new SnapshotParameters(), null);
            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            ImageIO.write(SwingFXUtils.fromFXImage(fxImage, null), "png", byteOutput);

            Image pdfImage = Image.getInstance(byteOutput.toByteArray());
            pdfImage.setAlignment(Element.ALIGN_CENTER);
            float scaler = ((document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin()) / pdfImage.getWidth()) * 100;
            pdfImage.scalePercent(scaler);
            document.add(pdfImage);

            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("--- Artificial Intelligence Conclusion ---", subFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Predicted Status: " + lblStatus.getText(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BaseColor.BLUE)));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Reasoning & Analysis:", subFont));
            document.add(new Paragraph(lblReason.getText(), textFont));

            document.close();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Успех");
            alert.setHeaderText(null);
            alert.setContentText("Отчет успешно сгенерирован!");
            alert.showAndWait();

        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Ошибка экспорта");
            alert.setContentText("Не удалось создать PDF: " + ex.getMessage());
            alert.showAndWait();
        }
    }

    private void updateCardUI(MetricConfig cfg, double value, String status, String reason) {
        Label lblStatus = statusLabels.get(cfg.dbKey);
        Label lblReason = reasonLabels.get(cfg.dbKey);
        if (lblStatus == null) return;

        String valStr = (cfg.unit.equals("proc") || cfg.unit.equals("B") || cfg.unit.equals("count") || cfg.unit.isEmpty())
                ? String.format("%.0f", value)
                : String.format("%.1f", value);

        lblStatus.setText(String.format("Прогноз: %s %s [%s]", valStr, cfg.unit, status));
        lblReason.setText(reason);

        String cleanStatus = status == null ? "UNKNOWN" : status.trim().toUpperCase();
        String style = "-fx-padding: 5; -fx-background-radius: 5; -fx-font-weight: bold; -fx-text-fill: white;";

        if (cleanStatus.contains("OK")) lblStatus.setStyle(style + "-fx-background-color: #198754;");
        else if (cleanStatus.contains("WARN")) lblStatus.setStyle(style + "-fx-background-color: #ffc107; -fx-text-fill: black;");
        else if (cleanStatus.contains("ERROR") || cleanStatus.contains("CRIT")) lblStatus.setStyle(style + "-fx-background-color: #dc3545;");
        else lblStatus.setStyle(style + "-fx-background-color: #6c757d;");
    }

    private void updateGlobalStatus() {
        boolean error = false;
        boolean warn = false;
        for (Label lbl : statusLabels.values()) {
            if (lbl.getText().contains("ERROR") || lbl.getText().contains("CRIT")) error = true;
            if (lbl.getText().contains("WARN")) warn = true;
        }
        if (error) {
            globalStatusLabel.setText("СБОЙ СИСТЕМЫ ❌");
            globalStatusLabel.setTextFill(Color.RED);
        } else if (warn) {
            globalStatusLabel.setText("ВНИМАНИЕ ⚠️");
            globalStatusLabel.setTextFill(Color.ORANGE);
        } else {
            globalStatusLabel.setText("НОРМА ✅");
            globalStatusLabel.setTextFill(Color.GREEN);
        }
    }

    private VBox createStatusCard(MetricConfig cfg) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-border-color: #ccc; -fx-background-color: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 5, 0, 0, 0);");
        card.setPrefWidth(240);

        Label title = new Label(cfg.title);
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        Label status = new Label("---");
        status.setStyle("-fx-background-color: #f8f9fa; -fx-padding: 5;");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setAlignment(Pos.CENTER);

        Label reason = new Label("Нет данных");
        reason.setWrapText(true);
        reason.setFont(Font.font(11));
        reason.setTextFill(Color.GRAY);

        statusLabels.put(cfg.dbKey, status);
        reasonLabels.put(cfg.dbKey, reason);

        card.getChildren().addAll(title, status, reason);
        return card;
    }

    // --- ИНИЦИАЛИЗАЦИЯ МЕТРИК ---
    private void initMetrics() {
        activeMetrics.add(new MetricConfig("CPU Load", "cpuLoad", "%", "OK < 70, WARN > 70, ERROR > 90"));
        activeMetrics.add(new MetricConfig("Memory Used", "memoryUsedPercent", "%", "OK < 80, WARN > 90, ERROR > 95"));
        activeMetrics.add(new MetricConfig("Processes", "processCount", "proc", "OK < 300, WARN > 400, ERROR > 600"));
        activeMetrics.add(new MetricConfig("Temperature", "cpuTemperature", "°C", "OK < 75, WARN > 80, ERROR > 90"));

        activeMetrics.add(new MetricConfig("Net RX", "networkRxBytes", "KB/s", "Detect sudden spikes"));
        activeMetrics.add(new MetricConfig("Net TX", "networkTxBytes", "KB/s", "Detect sudden spikes"));

        try {
            List<String> disks = repository.getDiskMountPoints(DEVICE_ID);
            for (String disk : disks) {
                activeMetrics.add(new MetricConfig("Disk " + disk, "DISK:" + disk, "%", "OK < 85, WARN > 90, ERROR > 98"));
            }
        } catch (Exception e) {
            System.err.println("Ошибка дисков: " + e.getMessage());
        }
    }

    // Вспомогательный класс для хранения данных о прогнозе
    private static class ForecastRecord {
        List<MetricPoint> points;
        public ForecastRecord(List<MetricPoint> points) {
            this.points = points;
        }
    }

    private static class MetricConfig {
        String title, dbKey, unit, rules;
        public MetricConfig(String t, String d, String u, String r) {
            title = t; dbKey = d; unit = u; rules = r;
        }
    }
}