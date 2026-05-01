package org.k1den.ui;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
import javafx.stage.Modality;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.k1den.model.MetricPoint;
import org.k1den.repository.ClickHouseRepository;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.*;

public class MonitoringApp extends Application {

    private final ClickHouseRepository repository = new ClickHouseRepository();
    private String currentDeviceId;

    private final List<MetricConfig> activeMetrics = new ArrayList<>();
    private final Map<String, Label> statusLabels = new HashMap<>();
    private final Map<String, Label> reasonLabels = new HashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> historySeriesMap = new HashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> forecastSeriesMap = new HashMap<>();
    private final Map<String, LineChart<Number, Number>> chartsMap = new HashMap<>();

    private VBox centerContainer;
    private HBox pieChartsPanel;
    private final Map<String, javafx.scene.chart.PieChart.Data> pieCurrentDataMap = new HashMap<>();
    private final Map<String, javafx.scene.chart.PieChart.Data> pieRemainDataMap = new HashMap<>();
    private final Map<String, Label> piePercentLabelsMap = new HashMap<>();

    private Label globalStatusLabel;
    private ToggleButton modeToggleBtn;
    private HBox historyControls;
    private DatePicker datePicker;
    private TextField startTimeField;
    private TextField endTimeField;

    private TabPane tabPane;
    private VBox fleetSidebar;
    private FlowPane cardsPanel;
    private Stage mainStage;

    private Timeline autoRefreshTimeline;
    private boolean isLiveMode = true;

    private double xOffset = 0;
    private double yOffset = 0;

    private boolean isMaximizedCustom = false;
    private double normalX, normalY, normalWidth, normalHeight;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.mainStage = primaryStage;
        primaryStage.initStyle(javafx.stage.StageStyle.UNDECORATED);

        List<String> devices = repository.getAvailableDevices();
        currentDeviceId = devices.isEmpty() ? "unknown" : devices.get(0);

        primaryStage.setTitle("Мониторинг и прогнозирование");

        HBox header = new HBox(15);
        header.setPadding(new Insets(15));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #2b2b2b; -fx-text-fill: white;");

        Label lblSrv = new Label("Сервер:");
        lblSrv.setTextFill(Color.WHITE);

        ComboBox<String> deviceBox = new ComboBox<>();
        deviceBox.getItems().addAll(devices);
        deviceBox.setValue(currentDeviceId);
        deviceBox.setStyle("-fx-font-weight: bold;");
        deviceBox.setOnAction(e -> switchDevice(deviceBox.getValue()));

        Button btnExportPdf = createHeaderButton("📄 ЭКСПОРТ АУДИТА", "#f43f5e");
        btnExportPdf.setOnAction(e -> exportActiveTabToPdf(mainStage));

        Button btnAccuracy = createHeaderButton("🎯 ТОЧНОСТЬ (MAE)", "#14b8a6");
        btnAccuracy.setOnAction(e -> showAccuracyDialog());

        Button btnSettings = createHeaderButton("⚙ НАСТРОЙКИ", "#a1a1aa");
        btnSettings.setOnAction(e -> openSettingsDialog());

        modeToggleBtn = new ToggleButton("🟢 LIVE РЕЖИМ");
        modeToggleBtn.setStyle("-fx-background-color: rgba(34, 197, 94, 0.1); -fx-text-fill: #22c55e; -fx-border-color: rgba(34, 197, 94, 0.4); -fx-border-radius: 5; -fx-padding: 6 16 6 16; -fx-font-weight: bold; -fx-cursor: hand;");

        datePicker = new DatePicker(LocalDate.now());
        datePicker.setPrefWidth(120);
        startTimeField = new TextField("00:00");
        startTimeField.setPrefWidth(60);
        endTimeField = new TextField("23:59");
        endTimeField.setPrefWidth(60);
        Button loadHistoryBtn = new Button("Найти");
        loadHistoryBtn.setStyle("-fx-background-color: #0d6efd; -fx-text-fill: white;");

        historyControls = new HBox(5, new Label("Дата:"), datePicker, new Label(" С:"), startTimeField, new Label(" По:"), endTimeField, loadHistoryBtn);
        historyControls.setAlignment(Pos.CENTER_LEFT);
        historyControls.setVisible(false);
        historyControls.setManaged(false);

        for (javafx.scene.Node node : historyControls.getChildren()) {
            if (node instanceof Label) ((Label) node).setTextFill(Color.WHITE);
        }

        globalStatusLabel = new Label("ОЖИДАНИЕ...");
        globalStatusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        globalStatusLabel.setTextFill(Color.WHITE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(lblSrv, deviceBox, btnExportPdf, btnAccuracy, btnSettings, modeToggleBtn, historyControls, spacer, globalStatusLabel);

        modeToggleBtn.setOnAction(e -> toggleMode());
        loadHistoryBtn.setOnAction(e -> loadHistoricalData());
        btnExportPdf.setOnAction(e -> exportActiveTabToPdf(mainStage));

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        cardsPanel = new FlowPane();
        cardsPanel.setPadding(new Insets(20));
        cardsPanel.setHgap(15);
        cardsPanel.setVgap(15);
        cardsPanel.setStyle("-fx-background-color: transparent;");

        ScrollPane scrollPane = new ScrollPane(cardsPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(280);

        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_RIGHT);
        titleBar.setStyle("-fx-background-color: #1a1a1a;");

        Label appTitle = new Label("Мониторинг и прогнозирование");
        appTitle.setTextFill(Color.LIGHTGRAY);
        appTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        appTitle.setPadding(new Insets(0, 0, 0, 15));

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        Label minBtn = createWindowButton("—", false);
        minBtn.setOnMouseClicked(e -> primaryStage.setIconified(true));

        Label maxBtn = createWindowButton("□", false);
        maxBtn.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        maxBtn.setOnMouseClicked(e -> {
            if (isMaximizedCustom) {
                primaryStage.setX(normalX);
                primaryStage.setY(normalY);
                primaryStage.setWidth(normalWidth);
                primaryStage.setHeight(normalHeight);
                isMaximizedCustom = false;
            } else {
                normalX = primaryStage.getX();
                normalY = primaryStage.getY();
                normalWidth = primaryStage.getWidth();
                normalHeight = primaryStage.getHeight();

                javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                primaryStage.setX(bounds.getMinX());
                primaryStage.setY(bounds.getMinY());
                primaryStage.setWidth(bounds.getWidth());
                primaryStage.setHeight(bounds.getHeight());
                isMaximizedCustom = true;
            }
        });

        Label closeBtn = createWindowButton("✕", true);
        closeBtn.setOnMouseClicked(e -> Platform.exit());

        titleBar.getChildren().addAll(appTitle, titleSpacer, minBtn, maxBtn, closeBtn);

        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            primaryStage.setX(event.getScreenX() - xOffset);
            primaryStage.setY(event.getScreenY() - yOffset);
        });

        VBox topContainer = new VBox(titleBar, header);

        pieChartsPanel = new HBox(15);
        pieChartsPanel.setAlignment(Pos.CENTER_LEFT);
        pieChartsPanel.setPadding(new Insets(10, 10, 10, 10));

        ScrollPane pieScroll = new ScrollPane(pieChartsPanel);
        pieScroll.setFitToHeight(true);
        pieScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pieScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pieScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        pieScroll.setMinHeight(160);

        centerContainer = new VBox(tabPane, pieScroll);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(topContainer);
        root.setCenter(centerContainer);
        root.setBottom(scrollPane);

        updateFleetSidebar();
        root.setRight(fleetSidebar);

        rebuildMetricsUI();
        startAutoRefresh();

        Scene scene = new Scene(root, 1450, 900);

        try {
            scene.getStylesheets().add(getClass().getResource("/dark-theme.css").toExternalForm());
        } catch (NullPointerException e) {
            System.err.println("Файл dark-theme.css не найден в папке resources!");
        }

        primaryStage.setScene(scene);

        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getVisualBounds();

        normalWidth = 1450;
        normalHeight = 900;
        normalX = bounds.getMinX() + (bounds.getWidth() - normalWidth) / 2;
        normalY = bounds.getMinY() + (bounds.getHeight() - normalHeight) / 2;

        primaryStage.setX(bounds.getMinX());
        primaryStage.setY(bounds.getMinY());
        primaryStage.setWidth(bounds.getWidth());
        primaryStage.setHeight(bounds.getHeight());

        isMaximizedCustom = true;

        primaryStage.show();
    }

    private void toggleMode() {
        isLiveMode = !modeToggleBtn.isSelected();
        if (isLiveMode) {
            modeToggleBtn.setText("🟢 LIVE РЕЖИМ");
            modeToggleBtn.setStyle("-fx-background-color: rgba(34, 197, 94, 0.1); -fx-text-fill: #22c55e; -fx-border-color: rgba(34, 197, 94, 0.4); -fx-border-radius: 5; -fx-padding: 6 16 6 16; -fx-font-weight: bold; -fx-cursor: hand;");
            historyControls.setVisible(false);
            historyControls.setManaged(false);
            startAutoRefresh();
        } else {
            modeToggleBtn.setText("🕒 АНАЛИЗ ПРОШЛОГО");
            modeToggleBtn.setStyle("-fx-background-color: rgba(249, 115, 22, 0.1); -fx-text-fill: #f97316; -fx-border-color: rgba(249, 115, 22, 0.4); -fx-border-radius: 5; -fx-padding: 6 16 6 16; -fx-font-weight: bold; -fx-cursor: hand;");
            historyControls.setVisible(true);
            historyControls.setManaged(true);
            if (autoRefreshTimeline != null) autoRefreshTimeline.stop();
            globalStatusLabel.setText("РЕЖИМ ИСТОРИИ 🕒");
            globalStatusLabel.setTextFill(Color.ORANGE);
        }
    }

    private void startAutoRefresh() {
        if (autoRefreshTimeline != null) autoRefreshTimeline.stop();
        autoRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(5), event -> refreshLiveData()));
        autoRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimeline.play();
        refreshLiveData();
    }

    private void switchDevice(String newDeviceId) {
        if (newDeviceId == null || newDeviceId.equals(currentDeviceId)) return;
        currentDeviceId = newDeviceId;
        mainStage.setTitle("Мониторинг и прогнозирование");
        rebuildMetricsUI();
        if (isLiveMode) refreshLiveData();
        else loadHistoricalData();
    }

    private void rebuildMetricsUI() {
        activeMetrics.clear();
        statusLabels.clear();
        reasonLabels.clear();
        historySeriesMap.clear();
        forecastSeriesMap.clear();
        chartsMap.clear();
        tabPane.getTabs().clear();
        cardsPanel.getChildren().clear();

        pieCurrentDataMap.clear();
        pieRemainDataMap.clear();
        piePercentLabelsMap.clear();
        if (pieChartsPanel != null) pieChartsPanel.getChildren().clear();

        initMetricsForDevice();

        for (MetricConfig cfg : activeMetrics) {
            tabPane.getTabs().add(createChartTab(cfg));

            cardsPanel.getChildren().add(createStatusCard(cfg));

            if (pieChartsPanel != null && !cfg.dbKey.contains("network")) {
                pieChartsPanel.getChildren().add(createPieChartWidget(cfg));
            }
        }
    }

    private void refreshLiveData() {
        new Thread(() -> {
            for (MetricConfig cfg : activeMetrics) {
                List<MetricPoint> historyData = repository.getMetricsForLastMinutes(currentDeviceId, cfg.dbKey, 30);
                ClickHouseRepository.PredictionData prediction = repository.getLatestPrediction(currentDeviceId, cfg.dbKey);

                Platform.runLater(() -> {
                    updateChart(cfg.dbKey, historyData, prediction.points);

                    double currentRealVal = historyData.isEmpty() ? Double.NaN : historyData.get(historyData.size() - 1).value;

                    double predictedVal = prediction.points.isEmpty() ? currentRealVal : prediction.points.get(prediction.points.size() - 1).value;

                    updateCardUI(cfg, predictedVal, prediction.status, prediction.reason, "Прогноз: ");

                    updatePieChart(cfg, currentRealVal);
                });
            }
            Platform.runLater(this::updateGlobalStatus);
            updateFleetSidebar();
        }).start();
    }

    private void loadHistoricalData() {
        try {
            LocalDate date = datePicker.getValue();
            LocalTime startTime = LocalTime.parse(startTimeField.getText());
            LocalTime endTime = LocalTime.parse(endTimeField.getText());

            ZoneId zoneId = ZoneId.systemDefault();
            long startMs = date.atTime(startTime).atZone(zoneId).toInstant().toEpochMilli();
            long endMs = date.atTime(endTime).atZone(zoneId).toInstant().toEpochMilli();

            if (startMs >= endMs) {
                showAlert("Ошибка", "Время начала должно быть меньше времени конца!");
                return;
            }

            new Thread(() -> {
                for (MetricConfig cfg : activeMetrics) {
                    List<MetricPoint> historyData = repository.getMetricsBetween(currentDeviceId, cfg.dbKey, startMs, endMs);

                    double maxVal = 0;
                    for (MetricPoint p : historyData) if (p.value > maxVal) maxVal = p.value;
                    final double finalMax = maxVal;

                    Platform.runLater(() -> {
                        updateChart(cfg.dbKey, historyData, new ArrayList<>());
                        updateCardUI(cfg, finalMax, "INFO", "Данные за " + date.toString(), "Пик: ");
                        updatePieChart(cfg, finalMax);
                    });
                }
            }).start();

        } catch (Exception e) {
            showAlert("Ошибка формата", "Введите время в формате ЧЧ:ММ (например, 09:30)");
        }
    }

    private void updateChart(String dbKey, List<MetricPoint> historyData, List<MetricPoint> forecastData) {
        XYChart.Series<Number, Number> histSeries = historySeriesMap.get(dbKey);
        XYChart.Series<Number, Number> foreSeries = forecastSeriesMap.get(dbKey);

        if (histSeries != null) {
            histSeries.getData().clear();
            for (MetricPoint p : historyData) {
                if (!Double.isNaN(p.value)) {
                    histSeries.getData().add(new XYChart.Data<>(p.timestamp, p.value));
                }
            }
        }

        if (foreSeries != null) {
            foreSeries.getData().clear();
            LineChart<Number, Number> chart = chartsMap.get(dbKey);
            if (chart != null) {
                if (isLiveMode && forecastData != null && !forecastData.isEmpty()) {
                    if (!chart.getData().contains(foreSeries)) {
                        chart.getData().add(foreSeries);
                    }
                    for (MetricPoint p : forecastData) {
                        if (!Double.isNaN(p.value)) {
                            foreSeries.getData().add(new XYChart.Data<>(p.timestamp, p.value));
                        }
                    }
                } else {
                    chart.getData().remove(foreSeries);
                }
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
                return sdf.format(new java.util.Date(object.longValue()));
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
        } else {
            yAxis.setForceZeroInRange(false);
        }

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(cfg.title);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);

        XYChart.Series<Number, Number> historySeries = new XYChart.Series<>();
        historySeries.setName("История (Реальность)");

        XYChart.Series<Number, Number> forecastSeries = new XYChart.Series<>();
        forecastSeries.setName("Прогноз");

        chart.getData().addAll(historySeries, forecastSeries);

        historySeriesMap.put(cfg.dbKey, historySeries);
        forecastSeriesMap.put(cfg.dbKey, forecastSeries);
        chartsMap.put(cfg.dbKey, chart);

        VBox box = new VBox(10, chart);
        box.setPadding(new Insets(10));
        return new Tab(cfg.title, box);
    }

    private void updateCardUI(MetricConfig cfg, double value, String status, String reason, String prefix) {
        Label lblStatus = statusLabels.get(cfg.dbKey);
        Label lblReason = reasonLabels.get(cfg.dbKey);

        if (lblStatus == null || lblReason == null) return;

        if (Double.isNaN(value)) {
            lblStatus.setText("НЕДОСТУПНО");
            lblStatus.setStyle("-fx-padding: 5; -fx-background-radius: 5; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: #6c757d;");
            lblReason.setText("Датчик не отвечает / Служба недоступна");

            return;
        }

        String valStr = (cfg.unit.equals("proc") || cfg.unit.equals("B/s") || cfg.unit.isEmpty())
                ? String.format("%.0f", value)
                : String.format("%.1f", value);

        lblStatus.setText(String.format("%s%s %s [%s]", prefix, valStr, cfg.unit, status));
        lblReason.setText(reason);

        String cleanStatus = status == null ? "UNKNOWN" : status.trim().toUpperCase();
        String style = "-fx-padding: 5; -fx-background-radius: 5; -fx-font-weight: bold; -fx-text-fill: white;";

        if (cleanStatus.contains("OK")) lblStatus.setStyle(style + "-fx-background-color: #198754;");
        else if (cleanStatus.contains("WARN"))
            lblStatus.setStyle(style + "-fx-background-color: #ffc107; -fx-text-fill: black;");
        else if (cleanStatus.contains("ERROR") || cleanStatus.contains("CRIT"))
            lblStatus.setStyle(style + "-fx-background-color: #dc3545;");
        else
            lblStatus.setStyle(style + "-fx-background-color: #0dcaf0; -fx-text-fill: black;");
    }

    private void openSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initStyle(javafx.stage.StageStyle.UNDECORATED);
        dialog.initOwner(mainStage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle("-fx-background-color: #1a1a1a;");
        titleBar.setPrefHeight(32);

        Label titleLabel = new Label("  ⚙  Настройки прогнозирования");
        titleLabel.setTextFill(Color.LIGHTGRAY);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        titleLabel.setPadding(new Insets(0, 0, 0, 6));

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        Label closeBtn = createWindowButton("\u2715", true);
        closeBtn.setOnMouseClicked(e -> dialog.close());

        titleBar.getChildren().addAll(titleLabel, titleSpacer, closeBtn);

        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> { dragOffset[0] = e.getSceneX(); dragOffset[1] = e.getSceneY(); });
        titleBar.setOnMouseDragged(e -> { dialog.setX(e.getScreenX() - dragOffset[0]); dialog.setY(e.getScreenY() - dragOffset[1]); });

        double[] currentSettings = repository.getSettings();

        Label subtitle = new Label("Параметры математической модели (LIVE)");
        subtitle.setFont(Font.font("System", FontWeight.BOLD, 13));
        subtitle.setTextFill(Color.web("#6c757d"));

        Slider forecastSlider = new Slider(5, 60, currentSettings[0]);
        forecastSlider.setShowTickLabels(true);
        forecastSlider.setShowTickMarks(true);
        forecastSlider.setMajorTickUnit(5);
        forecastSlider.setBlockIncrement(5);
        forecastSlider.setSnapToTicks(true);

        Label forecastLabel = new Label("Горизонт прогноза: " + (int) forecastSlider.getValue() + " мин.");
        forecastLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        forecastLabel.setTextFill(Color.WHITE);
        forecastSlider.valueProperty().addListener((obs, old, val) ->
                forecastLabel.setText("Горизонт прогноза: " + val.intValue() + " мин."));

        Slider sensSlider = new Slider(1.0, 6.0, currentSettings[1]);
        sensSlider.setShowTickLabels(true);
        sensSlider.setShowTickMarks(true);
        sensSlider.setMajorTickUnit(1);
        sensSlider.setBlockIncrement(0.5);

        Label sensLabel = new Label("Чувствительность к скачкам: " + String.format("%.1f", sensSlider.getValue()));
        sensLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        sensLabel.setTextFill(Color.WHITE);
        Label sensHint = new Label("(1.0 = Строгая, реагирует на всё | 6.0 = Мягкая, игнорирует шум)");
        sensHint.setTextFill(Color.GRAY);
        sensHint.setWrapText(true);
        sensSlider.valueProperty().addListener((obs, old, val) ->
                sensLabel.setText("Чувствительность к скачкам: " + String.format("%.1f", val.doubleValue())));

        VBox content = new VBox(15, subtitle, forecastLabel, forecastSlider, sensLabel, sensHint, sensSlider);
        content.setPadding(new Insets(20));
        content.setPrefWidth(380);
        content.setStyle("-fx-background-color: #2b2b2b;");

        Button okBtn = new Button("OK");
        okBtn.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 6 24 6 24;");
        okBtn.setOnAction(e -> {
            repository.saveSettings((int) forecastSlider.getValue(), sensSlider.getValue());
            refreshLiveData();
            dialog.close();
        });

        Button cancelBtn = new Button("Отмена");
        cancelBtn.setStyle("-fx-background-color: #3f3f46; -fx-text-fill: #cccccc; -fx-cursor: hand; -fx-padding: 6 16 6 16;");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox btnRow = new HBox(10, cancelBtn, okBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));
        content.getChildren().add(btnRow);

        VBox root = new VBox(titleBar, content);
        root.setStyle("-fx-border-color: #444444; -fx-border-width: 1;");

        Scene scene = new Scene(root);
        try {
            scene.getStylesheets().add(getClass().getResource("/dark-theme.css").toExternalForm());
        } catch (Exception ignored) {}

        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void updateGlobalStatus() {
        if (!isLiveMode) return;

        boolean error = false, warn = false;
        for (Label lbl : statusLabels.values()) {
            if (lbl.getText().contains("ERROR") || lbl.getText().contains("CRIT")) error = true;
            if (lbl.getText().contains("WARN")) warn = true;
        }
        if (error) {
            globalStatusLabel.setText("СБОЙ СИСТЕМЫ");
            globalStatusLabel.setTextFill(Color.web("#ff4444"));
        } else if (warn) {
            globalStatusLabel.setText("ВНИМАНИЕ");
            globalStatusLabel.setTextFill(Color.web("#ffbb33"));
        } else {
            globalStatusLabel.setText("СИСТЕМА СТАБИЛЬНА");
            globalStatusLabel.setTextFill(Color.web("#00C851"));
        }
    }

    private VBox createPieChartWidget(MetricConfig cfg) {
        javafx.scene.chart.PieChart pieChart = new javafx.scene.chart.PieChart();
        pieChart.getStyleClass().add("status-pie");

        pieChart.setPrefSize(130, 130);
        pieChart.setMinSize(130, 130);
        pieChart.setMaxSize(130, 130);
        pieChart.setLegendVisible(false);
        pieChart.setLabelsVisible(false);

        javafx.scene.chart.PieChart.Data currentData = new javafx.scene.chart.PieChart.Data("Занято", 0);
        javafx.scene.chart.PieChart.Data remainData = new javafx.scene.chart.PieChart.Data("Свободно", cfg.maxValue);
        pieChart.getData().addAll(currentData, remainData);

        pieCurrentDataMap.put(cfg.dbKey, currentData);
        pieRemainDataMap.put(cfg.dbKey, remainData);

        Label title = new Label(cfg.title);
        title.setTextFill(Color.LIGHTGRAY);
        title.setFont(Font.font("System", FontWeight.BOLD, 11));

        Label percentLabel = new Label("Занято: 0.0%");
        percentLabel.setTextFill(Color.WHITE);
        percentLabel.setFont(Font.font("System", 12));
        piePercentLabelsMap.put(cfg.dbKey, percentLabel);

        VBox box = new VBox(2, pieChart, title, percentLabel);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void updatePieChart(MetricConfig cfg, double value) {
        javafx.scene.chart.PieChart.Data current = pieCurrentDataMap.get(cfg.dbKey);
        javafx.scene.chart.PieChart.Data remain = pieRemainDataMap.get(cfg.dbKey);
        Label percentLabel = piePercentLabelsMap.get(cfg.dbKey);

        if (current != null && remain != null && percentLabel != null) {

            if (Double.isNaN(value)) {
                current.setPieValue(0);
                remain.setPieValue(cfg.maxValue);
                percentLabel.setText("НЕДОСТУПНО");
                percentLabel.setTextFill(Color.web("#6c757d"));
                return;
            }

            double safeValue = Math.min(value, cfg.maxValue);
            current.setPieValue(safeValue);
            remain.setPieValue(Math.max(0, cfg.maxValue - safeValue));

            String labelText;
            if (isLiveMode) {
                switch (cfg.dbKey) {
                    case "cpuLoad": labelText = String.format("Загружен на %.1f%%", value); break;
                    case "memoryUsedPercent": labelText = String.format("Занято %.1f%%", value); break;
                    case "cpuTemperature": labelText = String.format("%.1f °C", value); break;
                    case "processCount": labelText = String.format("Общее количество процессов: %.0f", value); break;
                    default: labelText = String.format("Занято %.1f%%", value); break;
                }
            } else {
                switch (cfg.dbKey) {
                    case "cpuLoad":
                    case "memoryUsedPercent":
                    case "DISK:": labelText = String.format("Максимальное достигнутое значение: %.1f%%", value); break;
                    case "cpuTemperature": labelText = String.format("Максимальное достигнутое значение: %.1f °C", value); break;
                    case "processCount": labelText = String.format("Максимальное количество процессов: %.0f", value); break;
                    default: labelText = String.format("Макс. значение: %.1f %s", value, cfg.unit); break;
                }
            }
            percentLabel.setText(labelText);

            double percentOccupied = (safeValue / cfg.maxValue) * 100.0;
            if (percentOccupied >= 85.0) {
                percentLabel.setTextFill(Color.web("#dc3545"));
            } else {
                percentLabel.setTextFill(Color.WHITE);
            }
        }
    }

    private VBox createStatusCard(MetricConfig cfg) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(10));
        card.getStyleClass().add("status-card");
        card.setPrefWidth(240);

        Label title = new Label(cfg.title);
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        Label status = new Label("Загрузка...");
        status.setStyle("-fx-padding: 5;");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setAlignment(Pos.CENTER);

        Label reason = new Label("Подключение к БД...");
        reason.setWrapText(true);
        reason.setFont(Font.font(11));
        reason.setTextFill(Color.GRAY);

        statusLabels.put(cfg.dbKey, status);
        reasonLabels.put(cfg.dbKey, reason);

        card.getChildren().addAll(title, status, reason);
        return card;
    }

    private void initMetricsForDevice() {
        activeMetrics.add(new MetricConfig("Загрузка CPU", "cpuLoad", "%", 100));
        activeMetrics.add(new MetricConfig("Использование Памяти", "memoryUsedPercent", "%", 100));
        activeMetrics.add(new MetricConfig("Температура", "cpuTemperature", "°C", 100));
        activeMetrics.add(new MetricConfig("Количество Процессов", "processCount", "proc", 1000));
        activeMetrics.add(new MetricConfig("Сеть (Входящий)", "networkRxBytes", "B/s", 10000000));
        activeMetrics.add(new MetricConfig("Сеть (Исходящий)", "networkTxBytes", "B/s", 1000000));

        List<String> diskMountPoints = repository.getDiskMountPoints(currentDeviceId);

        for (String mountPoint : diskMountPoints) {
            String dbKey = "DISK:" + mountPoint;
            activeMetrics.add(new MetricConfig("Диск: " + mountPoint, dbKey, "%", 100));
        }
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void exportActiveTabToPdf(Stage stage) {
        int activeTabIndex = tabPane.getSelectionModel().getSelectedIndex();
        if (activeTabIndex < 0 || activeTabIndex >= activeMetrics.size()) return;

        MetricConfig activeCfg = activeMetrics.get(activeTabIndex);
        LineChart<Number, Number> chart = chartsMap.get(activeCfg.dbKey);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Сохранить PDF отчет");
        fileChooser.setInitialFileName("System_Audit_" + currentDeviceId + ".pdf");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));

        File file = fileChooser.showSaveDialog(stage);
        if (file == null) return;

        try {
            Document document = new Document(PageSize.A4, 50, 50, 40, 40);
            PdfWriter.getInstance(document, new FileOutputStream(file));
            document.open();

            com.itextpdf.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, BaseColor.BLACK);
            com.itextpdf.text.Font subTitleFont = FontFactory.getFont(FontFactory.HELVETICA, 14, BaseColor.DARK_GRAY);
            com.itextpdf.text.Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BaseColor.WHITE);
            com.itextpdf.text.Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11, BaseColor.BLACK);
            com.itextpdf.text.Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, BaseColor.BLACK);

            try {
                String fontPath = "C:/Windows/Fonts/arial.ttf";
                titleFont = FontFactory.getFont(fontPath, "Cp1251", true, 22, com.itextpdf.text.Font.BOLD, new BaseColor(33, 37, 41));
                subTitleFont = FontFactory.getFont(fontPath, "Cp1251", true, 13, com.itextpdf.text.Font.NORMAL, new BaseColor(108, 117, 125));
                tableHeaderFont = FontFactory.getFont(fontPath, "Cp1251", true, 12, com.itextpdf.text.Font.BOLD, BaseColor.WHITE);
                normalFont = FontFactory.getFont(fontPath, "Cp1251", true, 11, com.itextpdf.text.Font.NORMAL, new BaseColor(52, 58, 64));
                boldFont = FontFactory.getFont(fontPath, "Cp1251", true, 11, com.itextpdf.text.Font.BOLD, new BaseColor(52, 58, 64));
            } catch (Exception e) {
                System.out.println("Системный шрифт Arial не найден. Используем стандартный (Возможны проблемы с кириллицей).");
            }

            Paragraph title = new Paragraph("Отчет о состоянии инфраструктуры", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(5f);
            document.add(title);

            Paragraph subtitle = new Paragraph("Генерация: " + new SimpleDateFormat("dd MMMM yyyy, HH:mm:ss").format(new java.util.Date()), subTitleFont);
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(20f);
            document.add(subtitle);

            com.itextpdf.text.pdf.draw.LineSeparator ls = new com.itextpdf.text.pdf.draw.LineSeparator();
            ls.setLineColor(new BaseColor(222, 226, 230));
            document.add(new Chunk(ls));
            document.add(Chunk.NEWLINE);

            Paragraph nodeInfo = new Paragraph();
            nodeInfo.add(new Chunk("Анализируемый узел: ", normalFont));
            nodeInfo.add(new Chunk(currentDeviceId, boldFont));
            nodeInfo.setSpacingAfter(15f);
            document.add(nodeInfo);

            com.itextpdf.text.pdf.PdfPTable table = new com.itextpdf.text.pdf.PdfPTable(3);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{1.5f, 1.2f, 2.5f});
            table.setSpacingAfter(25f);

            String[] headers = {"Метрика", "Текущий Статус", "Детализация / Прогноз"};
            BaseColor headerBgColor = new BaseColor(52, 58, 64);

            for (String header : headers) {
                com.itextpdf.text.pdf.PdfPCell cell = new com.itextpdf.text.pdf.PdfPCell(new Phrase(header, tableHeaderFont));
                cell.setBackgroundColor(headerBgColor);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPadding(8f);
                cell.setBorderColor(new BaseColor(222, 226, 230));
                table.addCell(cell);
            }

            boolean isAlternateRow = false;
            BaseColor altRowColor = new BaseColor(248, 249, 250);

            for (MetricConfig cfg : activeMetrics) {
                String statusText = statusLabels.get(cfg.dbKey).getText();
                String reasonText = reasonLabels.get(cfg.dbKey).getText();

                com.itextpdf.text.Font statusFont = new com.itextpdf.text.Font(boldFont);
                String cleanStatus = statusText.toUpperCase();
                if (cleanStatus.contains("OK")) {
                    statusFont.setColor(new BaseColor(25, 135, 84));
                } else if (cleanStatus.contains("WARN")) {
                    statusFont.setColor(new BaseColor(217, 119, 6));
                } else if (cleanStatus.contains("ERROR") || cleanStatus.contains("CRIT")) {
                    statusFont.setColor(new BaseColor(220, 53, 69));
                } else {
                    statusFont.setColor(new BaseColor(13, 202, 240));
                }

                com.itextpdf.text.pdf.PdfPCell cellName = new com.itextpdf.text.pdf.PdfPCell(new Phrase(cfg.title, normalFont));
                cellName.setPadding(8f);
                cellName.setVerticalAlignment(Element.ALIGN_MIDDLE);

                com.itextpdf.text.pdf.PdfPCell cellStatus = new com.itextpdf.text.pdf.PdfPCell(new Phrase(statusText, statusFont));
                cellStatus.setPadding(8f);
                cellStatus.setHorizontalAlignment(Element.ALIGN_CENTER);
                cellStatus.setVerticalAlignment(Element.ALIGN_MIDDLE);

                com.itextpdf.text.pdf.PdfPCell cellReason = new com.itextpdf.text.pdf.PdfPCell(new Phrase(reasonText, normalFont));
                cellReason.setPadding(8f);
                cellReason.setVerticalAlignment(Element.ALIGN_MIDDLE);

                if (isAlternateRow) {
                    cellName.setBackgroundColor(altRowColor);
                    cellStatus.setBackgroundColor(altRowColor);
                    cellReason.setBackgroundColor(altRowColor);
                }

                BaseColor borderColor = new BaseColor(222, 226, 230);
                cellName.setBorderColor(borderColor);
                cellStatus.setBorderColor(borderColor);
                cellReason.setBorderColor(borderColor);

                table.addCell(cellName);
                table.addCell(cellStatus);
                table.addCell(cellReason);

                isAlternateRow = !isAlternateRow;
            }
            document.add(table);

            Paragraph chartTitle = new Paragraph("Детальный снимок аналитики: " + activeCfg.title, boldFont);
            chartTitle.setSpacingAfter(10f);
            document.add(chartTitle);

            WritableImage fxImage = chart.snapshot(new SnapshotParameters(), null);
            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            ImageIO.write(SwingFXUtils.fromFXImage(fxImage, null), "png", byteOutput);

            Image pdfImage = Image.getInstance(byteOutput.toByteArray());
            pdfImage.setAlignment(Element.ALIGN_CENTER);

            float scaler = ((document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin()) / pdfImage.getWidth()) * 100;
            pdfImage.scalePercent(scaler);

            pdfImage.setBorder(Rectangle.BOX);
            pdfImage.setBorderColor(new BaseColor(206, 212, 218));
            pdfImage.setBorderWidth(1f);

            document.add(pdfImage);

            document.add(new Chunk(ls));
            Paragraph footer = new Paragraph("Сгенерировано автоматически системой интеллектуального мониторинга",
                    FontFactory.getFont("C:/Windows/Fonts/arial.ttf", "Cp1251", true, 9, com.itextpdf.text.Font.ITALIC, new BaseColor(173, 181, 189)));
            footer.setAlignment(Element.ALIGN_RIGHT);
            footer.setSpacingBefore(5f);
            document.add(footer);

            document.close();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setHeaderText("Успешно!");
            alert.setContentText("Детальный PDF-отчет сохранен.");
            alert.showAndWait();

        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setHeaderText("Ошибка генерации");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        }
    }

    private Label createWindowButton(String text, boolean isClose) {
        Label btn = new Label(text);
        btn.setPrefSize(46, 30);
        btn.setAlignment(Pos.CENTER);
        btn.setFont(Font.font("Segoe UI", 14));
        btn.setTextFill(Color.web("#999999"));

        btn.setOnMouseEntered(e -> {
            if (isClose) {
                btn.setStyle("-fx-background-color: #E81123; -fx-cursor: hand;");
            } else {
                btn.setStyle("-fx-background-color: #333333; -fx-cursor: hand;");
            }
            btn.setTextFill(Color.WHITE);
        });

        btn.setOnMouseExited(e -> {
            btn.setStyle("-fx-background-color: transparent;");
            btn.setTextFill(Color.web("#999999"));
        });

        return btn;
    }

    private void updateFleetSidebar() {
        if (fleetSidebar == null) {
            fleetSidebar = new VBox(8);
            fleetSidebar.setPadding(new Insets(15));
            fleetSidebar.setPrefWidth(220);

            fleetSidebar.setStyle("-fx-background-color: #1e1e1e; -fx-border-color: #444444; -fx-border-width: 0 0 0 1;");
        }

        new Thread(() -> {
            List<String> devices = repository.getAvailableDevices();

            Map<String, String> statuses = new HashMap<>();
            for (String devId : devices) {
                statuses.put(devId, repository.getWorstStatusForDevice(devId));
            }

            Platform.runLater(() -> {
                fleetSidebar.getChildren().clear();

                Label title = new Label("СТАТУС ФЛОТА");
                title.setFont(Font.font("System", FontWeight.BOLD, 14));
                title.setTextFill(Color.GRAY);
                title.setPadding(new Insets(0, 0, 10, 0));
                fleetSidebar.getChildren().add(title);

                for (String devId : devices) {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(5, 10, 5, 10));

                    String status = statuses.get(devId);

                    Label dot = new Label("●");
                    dot.setFont(Font.font(26));
                    if ("ERROR".equals(status)) {
                        dot.setTextFill(Color.web("#dc3545"));
                    } else if ("WARN".equals(status)) {
                        dot.setTextFill(Color.web("#ffc107"));
                    } else {
                        dot.setTextFill(Color.web("#198754"));
                    }

                    Label name = new Label(devId);
                    name.setTextFill(Color.WHITE);
                    name.setFont(Font.font("System", 13));

                    if (devId.equals(currentDeviceId)) {
                        name.setFont(Font.font("System", FontWeight.BOLD, 13));
                        row.setStyle("-fx-background-color: #3f3f46; -fx-background-radius: 5;");
                    }

                    row.getChildren().addAll(dot, name);
                    fleetSidebar.getChildren().add(row);
                }
            });
        }).start();
    }

    private void showAccuracyDialog() {
        Stage dialog = new Stage();
        dialog.initStyle(javafx.stage.StageStyle.UNDECORATED);
        dialog.initOwner(mainStage);
        dialog.initModality(Modality.APPLICATION_MODAL);

        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle("-fx-background-color: #1a1a1a;");
        titleBar.setPrefHeight(32);

        Label titleLabel = new Label("  Оценка точности прогноза (MAE)");
        titleLabel.setTextFill(Color.LIGHTGRAY);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        titleLabel.setPadding(new Insets(0, 0, 0, 6));

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        Label closeBtn = createWindowButton("\u2715", true);
        closeBtn.setOnMouseClicked(e -> dialog.close());

        titleBar.getChildren().addAll(titleLabel, titleSpacer, closeBtn);

        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> { dragOffset[0] = e.getSceneX(); dragOffset[1] = e.getSceneY(); });
        titleBar.setOnMouseDragged(e -> { dialog.setX(e.getScreenX() - dragOffset[0]); dialog.setY(e.getScreenY() - dragOffset[1]); });

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setPrefWidth(500);
        content.setStyle("-fx-background-color: #2b2b2b;");

        Label subtitle = new Label("Аудит математической модели: " + currentDeviceId);
        subtitle.setFont(Font.font("System", FontWeight.BOLD, 13));
        subtitle.setTextFill(Color.web("#0d9488"));

        Label desc = new Label("Метрика MAE (Mean Absolute Error) рассчитывается на стороне БД (ClickHouse ASOF JOIN). " +
                "Показывает среднее отклонение предсказанного значения от фактического за последние 24 часа. " +
                "Чем меньше значение, тем точнее работает алгоритм.");
        desc.setTextFill(Color.GRAY);
        desc.setWrapText(true);

        content.getChildren().addAll(subtitle, desc);

        for (MetricConfig cfg : activeMetrics) {
            double mae = repository.calculateMAE(currentDeviceId, cfg.dbKey, 24);

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(7, 12, 7, 12));
            row.setStyle("-fx-background-color: #333333; -fx-background-radius: 6;");

            Label name = new Label(cfg.title + ":");
            name.setPrefWidth(220);
            name.setFont(Font.font("System", FontWeight.BOLD, 13));
            name.setTextFill(Color.WHITE);

            Label val = new Label();
            val.setFont(Font.font("System", FontWeight.BOLD, 13));

            if (mae < 0) {
                val.setText("Недостаточно истории");
                val.setTextFill(Color.web("#dc3545"));
            } else {
                val.setText(String.format("Отклонение: %.2f %s", mae, cfg.unit));
                if (mae < (cfg.maxValue * 0.05)) {
                    val.setTextFill(Color.web("#198754"));
                } else if (mae < (cfg.maxValue * 0.15)) {
                    val.setTextFill(Color.web("#ffc107"));
                } else {
                    val.setTextFill(Color.web("#dc3545"));
                }
            }
            row.getChildren().addAll(name, val);
            content.getChildren().add(row);
        }

        HBox btnRow = new HBox();
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(8, 0, 0, 0));
        content.getChildren().add(btnRow);

        VBox root = new VBox(titleBar, content);
        root.setStyle("-fx-border-color: #444444; -fx-border-width: 1;");

        Scene scene = new Scene(root);
        try {
            scene.getStylesheets().add(getClass().getResource("/dark-theme.css").toExternalForm());
        } catch (Exception ignored) {}

        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private Button createHeaderButton(String text, String accentColor) {
        Button btn = new Button(text);

        String defaultStyle = String.format(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: %s; " +
                        "-fx-border-color: %s; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 5; " +
                        "-fx-padding: 6 16 6 16; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand;", accentColor, accentColor);

        String hoverStyle = String.format(
                "-fx-background-color: %s; " +
                        "-fx-text-fill: #ffffff; " +
                        "-fx-border-color: %s; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 5; " +
                        "-fx-padding: 6 16 6 16; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand;", accentColor, accentColor);

        btn.setStyle(defaultStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(hoverStyle));
        btn.setOnMouseExited(e -> btn.setStyle(defaultStyle));

        return btn;
    }

    private static class MetricConfig {
        String title, dbKey, unit;
        double maxValue;

        public MetricConfig(String t, String d, String u, double max) {
            title = t;
            dbKey = d;
            unit = u;
            maxValue = max;
        }
    }
}