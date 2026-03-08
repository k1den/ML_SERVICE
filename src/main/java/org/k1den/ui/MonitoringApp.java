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
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
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
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MonitoringApp extends Application {

    private final ClickHouseRepository repository = new ClickHouseRepository();
    private final LlmService llmService = new LlmService();

    private String currentDeviceId;

    private final List<MetricConfig> activeMetrics = new ArrayList<>();
    private final Map<String, Label> statusLabels = new HashMap<>();
    private final Map<String, Label> reasonLabels = new HashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> historySeriesMap = new HashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> forecastSeriesMap = new HashMap<>();
    private final Map<String, LineChart<Number, Number>> chartsMap = new HashMap<>();
    private final Map<String, ForecastRecord> activeForecasts = new HashMap<>();

    // Выносим списки на уровень класса, чтобы не было NullPointerException
    private ComboBox<String> timeBox;
    private ComboBox<String> engineBox;

    private Label globalStatusLabel;
    private TabPane tabPane;
    private FlowPane cardsPanel;
    private Stage mainStage;

    private final javafx.animation.Timeline autoRefreshTimeline = new javafx.animation.Timeline();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.mainStage = primaryStage;

        List<String> devices = repository.getAvailableDevices();
        currentDeviceId = devices.get(0);

        primaryStage.setTitle("AI System Monitor: " + currentDeviceId);

        // --- ВЕРХНЯЯ ПАНЕЛЬ ---
        HBox header = new HBox(15);
        header.setPadding(new Insets(15));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        ComboBox<String> deviceBox = new ComboBox<>();
        deviceBox.getItems().addAll(devices);
        deviceBox.setValue(currentDeviceId);
        deviceBox.setStyle("-fx-font-weight: bold;");
        deviceBox.setOnAction(e -> switchDevice(deviceBox.getValue()));

        engineBox = new ComboBox<>();
        engineBox.getItems().addAll("AI (Llama 3)", "Математика (Своя)", "Apache Commons Math");
        engineBox.setValue("Apache Commons Math");

        timeBox = new ComboBox<>();
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

        header.getChildren().addAll(
                new Label("Сервер:"), deviceBox,
                new Label("Движок:"), engineBox,
                new Label("Горизонт:"), timeBox,
                btnPredict, btnExportPdf, spacer, globalStatusLabel
        );

        // --- ЦЕНТР И НИЗ ---
        tabPane = new TabPane();
        cardsPanel = new FlowPane();
        cardsPanel.setPadding(new Insets(20));
        cardsPanel.setHgap(15);
        cardsPanel.setVgap(15);
        cardsPanel.setStyle("-fx-background-color: white;");

        ScrollPane scrollPane = new ScrollPane(cardsPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(280);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(tabPane);
        root.setBottom(scrollPane);

        btnPredict.setOnAction(e -> runFullAnalysis());
        btnExportPdf.setOnAction(e -> exportActiveTabToPdf(mainStage, timeBox.getValue()));

        rebuildMetricsUI();

        primaryStage.setScene(new Scene(root, 1200, 900));
        primaryStage.show();
    }

    private void runFullAnalysis() {
        String timeStr = timeBox.getValue();
        String selectedEngine = engineBox.getValue();

        int minutes = Integer.parseInt(timeStr.split(" ")[0]);
        int totalPoints = minutes * 5;
        long totalDurationMs = minutes * 60 * 1000L;

        globalStatusLabel.setText("СБОР И АНАЛИЗ... ⏳");
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
                List<MetricPoint> data = repository.getMetricsForLastMinutes(currentDeviceId, cfg.dbKey, 30);
                if (data.size() < 2) return;

                List<Double> values = new ArrayList<>();
                for(MetricPoint p : data) values.add(p.value);

                List<MetricPoint> forecastPoints = new ArrayList<>();
                long startTime = data.get(data.size() - 1).timestamp;
                double lastVal = data.get(data.size() - 1).value;
                forecastPoints.add(new MetricPoint(startTime, lastVal));

                String status = "OK";
                String reason = "";
                double finalTargetValue = lastVal;

                if (selectedEngine.equals("AI (Llama 3)")) {
                    LlmService.LlmPrediction res = llmService.predict(values, minutes, cfg.title, cfg.rules);
                    status = res.status;
                    reason = res.reason;

                    if (res.predictedValues != null && !res.predictedValues.isEmpty()) {
                        int numAiPoints = res.predictedValues.size();
                        double aiStepMs = (double) totalDurationMs / numAiPoints;

                        for (int i = 0; i < numAiPoints; i++) {
                            long pointTime = startTime + (long) (aiStepMs * (i + 1));
                            forecastPoints.add(new MetricPoint(pointTime, res.predictedValues.get(i)));
                        }
                        finalTargetValue = res.predictedValues.get(numAiPoints - 1);
                    } else {
                        reason = "LLM не смогла сгенерировать массив.";
                    }

                } else if (selectedEngine.equals("Математика (Своя)")) {
                    // АЛГОРИТМ: Умная EMA (Экспоненциальная скользящая) + Контролируемый шум

                    // 1. Находим "здоровый" разброс графика (игнорируя дикие пики)
                    List<Double> sortedVals = new ArrayList<>(values);
                    Collections.sort(sortedVals);
                    double median = sortedVals.get(sortedVals.size() / 2); // Медиана не боится скачков до 100%

                    double variance = 0;
                    int validPoints = 0;
                    for (double v : values) {
                        // Считаем шум только по "нормальным" точкам
                        if (Math.abs(v - median) < 20.0) {
                            variance += Math.pow(v - median, 2);
                            validPoints++;
                        }
                    }
                    double stdDev = validPoints > 0 ? Math.sqrt(variance / validPoints) : 1.0;
                    if (stdDev < 1.0) stdDev = 1.0; // Гарантируем хотя бы минимальную дрожь

                    // 2. Сглаживаем историю для поиска РЕАЛЬНОГО уровня и тренда
                    double alpha = 0.15; // Сильное сглаживание: верим истории больше, чем последнему скачку
                    double smoothedLevel = values.get(0);
                    double trend = 0;

                    for (int i = 1; i < values.size(); i++) {
                        double currentVal = values.get(i);
                        double prevSmoothed = smoothedLevel;

                        // ФИЛЬТР: Если точка улетела дальше 3-х отклонений от медианы (тот самый скачок до 100%)
                        if (Math.abs(currentVal - median) > stdDev * 3) {
                            // "Обрезаем" пик, не давая ему сломать базу
                            currentVal = median + Math.signum(currentVal - median) * stdDev;
                        }

                        smoothedLevel = alpha * currentVal + (1 - alpha) * smoothedLevel;
                        trend = alpha * (smoothedLevel - prevSmoothed) + (1 - alpha) * trend;
                    }

                    // 3. Генерируем прогноз
                    long stepMs = totalDurationMs / totalPoints;
                    double phi = 0.95; // Затухание тренда

                    // ВАЖНО: Стартуем не от lastVal (который мог быть 100%), а от сглаженной базы!
                    double currentForecastLevel = smoothedLevel;

                    for (int i = 1; i <= totalPoints; i++) {
                        trend *= phi;
                        currentForecastLevel += trend;

                        // ВОЗВРАЩАЕМ ЖИЗНЬ ГРАФИКУ:
                        // Math.sin дает плавное волнообразное "дыхание", а random - мелкую аппаратную дрожь
                        double noise = Math.sin(i * 0.8) * (stdDev * 0.4) + (Math.random() - 0.5) * stdDev;
                        double predictedY = currentForecastLevel + noise;

                        // Жесткие лимиты
                        if (cfg.unit.equals("%")) predictedY = Math.min(100, Math.max(0, predictedY));
                        else predictedY = Math.max(0, predictedY);

                        forecastPoints.add(new MetricPoint(startTime + stepMs * i, predictedY));
                    }

                    // Финальное значение для правил берем БЕЗ шума, чтобы статус не моргал туда-сюда
                    finalTargetValue = currentForecastLevel;

                    // ПРИМЕНЯЕМ ПРАВИЛА
                    status = evaluateStatus(finalTargetValue, cfg.rules);
                    if (status.equals("ERROR")) reason = "Устойчивый тренд превышает лимиты (аномалии отфильтрованы).";
                    else if (status.equals("WARN")) reason = "Обнаружен рост базовой нагрузки.";
                    else reason = "Система стабильна (кратковременные пики проигнорированы).";

                } else {
                    org.apache.commons.math3.fitting.WeightedObservedPoints obs = new org.apache.commons.math3.fitting.WeightedObservedPoints();
                    for (int i = 0; i < data.size(); i++) {
                        obs.add(i, data.get(i).value);
                    }

                    org.apache.commons.math3.fitting.PolynomialCurveFitter fitter = org.apache.commons.math3.fitting.PolynomialCurveFitter.create(2);
                    double[] coeff = fitter.fit(obs.toList());

                    long stepMs = totalDurationMs / totalPoints;
                    for (int i = 1; i <= totalPoints; i++) {
                        double x = data.size() + i;
                        double predictedY = coeff[0] + coeff[1] * x + coeff[2] * x * x;

                        if (cfg.unit.equals("%")) predictedY = Math.min(100, Math.max(0, predictedY));
                        else predictedY = Math.max(0, predictedY);

                        forecastPoints.add(new MetricPoint(startTime + stepMs * i, predictedY));
                    }
                    finalTargetValue = forecastPoints.get(forecastPoints.size()-1).value;

                    // ПРИМЕНЯЕМ ПРАВИЛА
                    status = evaluateStatus(finalTargetValue, cfg.rules);
                    if (status.equals("ERROR")) reason = "Превышение лимитов! Полиномиальная дуга.";
                    else if (status.equals("WARN")) reason = "Внимание: график идет вверх. Полиномиальная дуга.";
                    else reason = "Система стабильна. Полиномиальная дуга.";
                }

                activeForecasts.put(cfg.dbKey, new ForecastRecord(forecastPoints));

                final String finalStatus = status;
                final String finalReason = reason;
                final double finalVal = finalTargetValue;

                Platform.runLater(() -> {
                    updateCardUI(cfg, finalVal, finalStatus, finalReason);
                    refreshChartData(cfg);
                });
            });
            tasks.add(task);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenRun(() -> {
            Platform.runLater(this::updateGlobalStatus);
        });
    }

    private void switchDevice(String newDeviceId) {
        if (newDeviceId == null || newDeviceId.equals(currentDeviceId)) return;
        currentDeviceId = newDeviceId;
        mainStage.setTitle("AI System Monitor: " + currentDeviceId);
        rebuildMetricsUI();
    }

    private void rebuildMetricsUI() {
        activeMetrics.clear();
        statusLabels.clear();
        reasonLabels.clear();
        historySeriesMap.clear();
        forecastSeriesMap.clear();
        chartsMap.clear();
        activeForecasts.clear();
        tabPane.getTabs().clear();
        cardsPanel.getChildren().clear();

        globalStatusLabel.setText("ОБНОВЛЕНИЕ...");
        globalStatusLabel.setTextFill(Color.GRAY);

        initMetricsForDevice(currentDeviceId);

        for (MetricConfig cfg : activeMetrics) {
            tabPane.getTabs().add(createChartTab(cfg));
            cardsPanel.getChildren().add(createStatusCard(cfg));
        }

        updateGlobalStatus();
    }

    private void refreshChartData(MetricConfig cfg) {
        XYChart.Series<Number, Number> histSeries = historySeriesMap.get(cfg.dbKey);
        XYChart.Series<Number, Number> foreSeries = forecastSeriesMap.get(cfg.dbKey);
        if (histSeries == null || foreSeries == null) return;

        histSeries.getData().clear();
        foreSeries.getData().clear();

        List<MetricPoint> data = repository.getMetricsForLastMinutes(currentDeviceId, cfg.dbKey, 30);
        for (MetricPoint p : data) {
            histSeries.getData().add(new XYChart.Data<>(p.timestamp, p.value));
        }

        ForecastRecord fr = activeForecasts.get(cfg.dbKey);
        if (fr != null && !data.isEmpty()) {
            for (MetricPoint fp : fr.points) {
                foreSeries.getData().add(new XYChart.Data<>(fp.timestamp, fp.value));
            }
        }
    }

    private Tab createChartTab(MetricConfig cfg) {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new StringConverter<Number>() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

            @Override
            public String toString(Number object) {
                return sdf.format(new Date(object.longValue()));
            }

            @Override
            public Number fromString(String string) {
                return 0;
            }
        });

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(cfg.unit);

        if (cfg.unit.equals("%")) {
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(0);
            yAxis.setUpperBound(100);
            yAxis.setTickUnit(10);
        } else if (cfg.unit.equals("°C")) {
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(20);
            yAxis.setUpperBound(100);
            yAxis.setTickUnit(10);
        } else {
            yAxis.setForceZeroInRange(false);
            yAxis.setTickLabelFormatter(new StringConverter<Number>() {
                @Override
                public String toString(Number object) {
                    return String.format("%.0f", object.doubleValue());
                }

                @Override
                public Number fromString(String string) {
                    return 0;
                }
            });
        }

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(cfg.title);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);

        XYChart.Series<Number, Number> historySeries = new XYChart.Series<>();
        historySeries.setName("История");

        XYChart.Series<Number, Number> forecastSeries = new XYChart.Series<>();
        forecastSeries.setName("Прогноз");

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

            document.add(new Paragraph("System Telemetry Forecast Report", titleFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Device ID: " + currentDeviceId, subFont));
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

            document.add(new Paragraph("--- Analysis Conclusion ---", subFont));
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

        String valStr = (cfg.unit.equals("proc") || cfg.unit.equals("KB/s") || cfg.unit.isEmpty())
                ? String.format("%.0f", value)
                : String.format("%.1f", value);

        lblStatus.setText(String.format("Прогноз: %s %s [%s]", valStr, cfg.unit, status));
        lblReason.setText(reason);

        String cleanStatus = status == null ? "UNKNOWN" : status.trim().toUpperCase();
        String style = "-fx-padding: 5; -fx-background-radius: 5; -fx-font-weight: bold; -fx-text-fill: white;";

        if (cleanStatus.contains("OK")) lblStatus.setStyle(style + "-fx-background-color: #198754;");
        else if (cleanStatus.contains("WARN"))
            lblStatus.setStyle(style + "-fx-background-color: #ffc107; -fx-text-fill: black;");
        else if (cleanStatus.contains("ERROR") || cleanStatus.contains("CRIT"))
            lblStatus.setStyle(style + "-fx-background-color: #dc3545;");
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

    private void initMetricsForDevice(String deviceId) {
        activeMetrics.add(new MetricConfig("CPU Load", "cpuLoad", "%", "OK < 70, WARN > 70, ERROR > 90"));
        activeMetrics.add(new MetricConfig("Memory Used", "memoryUsedPercent", "%", "OK < 80, WARN > 90, ERROR > 95"));
        activeMetrics.add(new MetricConfig("Processes", "processCount", "proc", "OK < 300, WARN > 400, ERROR > 600"));
        activeMetrics.add(new MetricConfig("Temperature", "cpuTemperature", "°C", "OK < 75, WARN > 80, ERROR > 90"));
        activeMetrics.add(new MetricConfig("Net RX", "networkRxBytes", "KB/s", "Detect sudden spikes"));
        activeMetrics.add(new MetricConfig("Net TX", "networkTxBytes", "KB/s", "Detect sudden spikes"));

        try {
            List<String> disks = repository.getDiskMountPoints(deviceId);
            for (String disk : disks) {
                activeMetrics.add(new MetricConfig("Disk " + disk, "DISK:" + disk, "%", "OK < 85, WARN > 90, ERROR > 98"));
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения дисков: " + e.getMessage());
        }
    }

    private static class ForecastRecord {
        List<MetricPoint> points;

        public ForecastRecord(List<MetricPoint> points) {
            this.points = points;
        }
    }

    // --- ПАРСЕР ПРАВИЛ ДЛЯ МАТЕМАТИКИ ---
    private String evaluateStatus(double predictedValue, String rules) {
        // Если правил нет или это текст (как у сети), возвращаем норму
        if (rules == null || (!rules.contains(">") && !rules.contains("<"))) return "OK";

        String finalStatus = "OK";
        try {
            String[] parts = rules.split(",");
            for (String part : parts) {
                part = part.trim();
                String[] tokens = part.split(" "); // Разбиваем "WARN > 70" на ["WARN", ">", "70"]
                if (tokens.length >= 3) {
                    String statusLvl = tokens[0];
                    String operator = tokens[1];
                    double threshold = Double.parseDouble(tokens[2]);

                    boolean match = false;
                    if (operator.equals(">") && predictedValue > threshold) match = true;
                    else if (operator.equals("<") && predictedValue < threshold) match = true;
                    else if (operator.equals(">=") && predictedValue >= threshold) match = true;
                    else if (operator.equals("<=") && predictedValue <= threshold) match = true;

                    if (match) {
                        // ERROR перебивает WARN
                        if (statusLvl.equals("ERROR")) finalStatus = "ERROR";
                        else if (statusLvl.equals("WARN") && !finalStatus.equals("ERROR")) finalStatus = "WARN";
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка парсинга правил: " + rules);
        }
        return finalStatus;
    }

    private static class MetricConfig {
        String title, dbKey, unit, rules;

        public MetricConfig(String t, String d, String u, String r) {
            title = t;
            dbKey = d;
            unit = u;
            rules = r;
        }
    }
}