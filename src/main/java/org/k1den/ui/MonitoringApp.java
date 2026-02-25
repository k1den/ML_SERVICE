package org.k1den.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.k1den.llm.LlmService;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MonitoringApp extends Application {

    private final ClickHouseRepository repository = new ClickHouseRepository();
    private final LlmService llmService = new LlmService();

    // !!! Укажи здесь ID своего устройства, как он записан в базе !!!
    private final String DEVICE_ID = "device-001";

    private final List<MetricConfig> activeMetrics = new ArrayList<>();
    private final Map<String, Label> statusLabels = new HashMap<>();
    private final Map<String, Label> reasonLabels = new HashMap<>();
    private Label globalStatusLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // 1. Инициализируем список метрик (включая поиск дисков)
        initMetrics();

        primaryStage.setTitle("AI System Monitor: " + DEVICE_ID);

        // --- HEADER ---
        HBox header = new HBox(15);
        header.setPadding(new Insets(15));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        ComboBox<String> timeBox = new ComboBox<>();
        timeBox.getItems().addAll("5 минут", "15 минут", "30 минут");
        timeBox.getSelectionModel().select(1);

        Button btnPredict = new Button("ЗАПУСТИТЬ ПРОГНОЗ");
        btnPredict.setStyle("-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");

        globalStatusLabel = new Label("ОЖИДАНИЕ...");
        globalStatusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        globalStatusLabel.setTextFill(Color.GRAY);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(new Label("Горизонт:"), timeBox, btnPredict, spacer, globalStatusLabel);

        // --- TABS (CENTER) ---
        TabPane tabPane = new TabPane();
        for (MetricConfig cfg : activeMetrics) {
            tabPane.getTabs().add(createChartTab(cfg));
        }

        // --- CARDS (BOTTOM) ---
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
        scrollPane.setPrefHeight(300);

        // --- ROOT LAYOUT ---
        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(tabPane);
        root.setBottom(scrollPane);

        // --- ACTION ---
        btnPredict.setOnAction(e -> runFullAnalysis(timeBox.getValue()));

        primaryStage.setScene(new Scene(root, 1200, 900));
        primaryStage.show();
    }

    // --- НАСТРОЙКА МЕТРИК ---
    private void initMetrics() {
        // Обычные метрики (cpu, ram, net, process)
        // ВАЖНО: unit="" для процессов, чтобы форматирование было целым числом
        activeMetrics.add(new MetricConfig("CPU Load", "cpuLoad", "%", "OK < 70, WARN > 70, ERROR > 90"));
        activeMetrics.add(new MetricConfig("Load Avg", "systemLoadAverage", "", "OK < 3, WARN > 4, ERROR > 8"));
        activeMetrics.add(new MetricConfig("Memory Used", "memoryUsedPercent", "%", "OK < 80, WARN > 90, ERROR > 95"));
        activeMetrics.add(new MetricConfig("Processes", "processCount", "proc", "OK < 300, WARN > 400, ERROR > 600"));
        activeMetrics.add(new MetricConfig("Temperature", "cpuTemperature", "°C", "OK < 75, WARN > 80, ERROR > 90"));

        // Сеть (без жестких правил, просто мониторинг спайков)
        activeMetrics.add(new MetricConfig("Net RX", "networkRxBytes", "B", "Detect sudden spikes"));
        activeMetrics.add(new MetricConfig("Net TX", "networkTxBytes", "B", "Detect sudden spikes"));

        // Диски (Динамический поиск)
        try {
            List<String> disks = repository.getDiskMountPoints(DEVICE_ID);
            for (String disk : disks) {
                // Префикс DISK: нужен для репозитория
                activeMetrics.add(new MetricConfig("Disk " + disk, "DISK:" + disk, "%", "OK < 85, WARN > 90, ERROR > 98"));
            }
        } catch (Exception e) {
            System.err.println("Ошибка загрузки дисков: " + e.getMessage());
        }
    }

    // --- ЛОГИКА АНАЛИЗА ---
    private void runFullAnalysis(String timeStr) {
        int minutes = Integer.parseInt(timeStr.split(" ")[0]);
        globalStatusLabel.setText("СБОР И АНАЛИЗ... ⏳");
        globalStatusLabel.setTextFill(Color.BLUE);

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (MetricConfig cfg : activeMetrics) {
            // Ставим статус "Загрузка"
            Platform.runLater(() -> {
                if(statusLabels.containsKey(cfg.dbKey)) {
                    statusLabels.get(cfg.dbKey).setText("...");
                    statusLabels.get(cfg.dbKey).setStyle("-fx-background-color: #eee;");
                }
            });

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                // 1. Данные
                List<MetricPoint> data = repository.getLastMetrics(DEVICE_ID, cfg.dbKey, 30);
                List<Double> values = new ArrayList<>();
                for(MetricPoint p : data) values.add(p.value);

                // 2. ЗАЩИТА ОТ НУЛЕЙ И ПУСТОТЫ
                // Если пусто или последнее значение 0 (для критичных метрик типа температуры)
                if (values.isEmpty() || (values.get(values.size()-1) == 0.0 && isCriticalMetric(cfg.dbKey))) {
                    Platform.runLater(() -> updateCardUI(cfg, 0, "NO DATA", "Сенсор недоступен или база пуста"));
                    return;
                }

                // 3. Запрос в LLM
                LlmService.LlmPrediction res = llmService.predict(values, minutes, cfg.title, cfg.rules);

                // 4. Обновление UI
                Platform.runLater(() -> updateCardUI(cfg, res.value, res.status, res.reason));
            });
            tasks.add(task);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenRun(() -> {
            Platform.runLater(this::updateGlobalStatus);
        });
    }

    private void updateCardUI(MetricConfig cfg, double value, String status, String reason) {
        Label lblStatus = statusLabels.get(cfg.dbKey);
        Label lblReason = reasonLabels.get(cfg.dbKey);

        if (lblStatus == null) return;

        // Форматирование чисел
        String valStr;
        if (cfg.unit.equals("proc") || cfg.unit.equals("B") || cfg.unit.equals("count") || cfg.unit.isEmpty()) {
            valStr = String.format("%.0f", value);
        } else {
            valStr = String.format("%.1f", value);
        }

        lblStatus.setText(String.format("%s %s [%s]", valStr, cfg.unit, status));
        lblReason.setText(reason);

        // --- ЛОГИКА ЦВЕТОВ (ИСПРАВЛЕНА) ---
        // Приводим к верхнему регистру и чистим пробелы
        String cleanStatus = (status == null) ? "UNKNOWN" : status.trim().toUpperCase();

        String baseStyle = "-fx-padding: 5; -fx-background-radius: 5; -fx-font-weight: bold; -fx-text-fill: white;";

        if (cleanStatus.contains("OK")) {
            lblStatus.setStyle(baseStyle + "-fx-background-color: #198754;"); // Зеленый
        }
        else if (cleanStatus.contains("WARN")) {
            // Теперь ловит и WARN, и WARNING, и Warning
            lblStatus.setStyle(baseStyle + "-fx-background-color: #ffc107; -fx-text-fill: black;"); // Желтый
        }
        else if (cleanStatus.contains("ERROR") || cleanStatus.contains("CRIT") || cleanStatus.contains("FAIL")) {
            lblStatus.setStyle(baseStyle + "-fx-background-color: #dc3545;"); // Красный
        }
        else {
            lblStatus.setStyle(baseStyle + "-fx-background-color: #6c757d;"); // Серый (если пришла дичь)
        }
    }

    private boolean isCriticalMetric(String key) {
        // Метрики, которые не могут быть нулем в нормальной жизни
        return key.equals("cpuTemperature") || key.equals("memoryUsedPercent");
    }

    private void updateGlobalStatus() {
        boolean error = false;
        boolean warn = false;
        for (Label lbl : statusLabels.values()) {
            if (lbl.getText().contains("ERROR")) error = true;
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

    // --- UI COMPONENTS CREATION ---
    private Tab createChartTab(MetricConfig cfg) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel(cfg.unit);
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setTitle(cfg.title);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);

        Button btnRefresh = new Button("Обновить");
        btnRefresh.setOnAction(e -> {
            chart.getData().clear();
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName("Live Data");
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            List<MetricPoint> data = repository.getLastMetrics(DEVICE_ID, cfg.dbKey, 50);
            for(MetricPoint p : data) series.getData().add(new XYChart.Data<>(sdf.format(new Date(p.timestamp)), p.value));
            chart.getData().add(series);
        });
        // Загружаем сразу при старте
        btnRefresh.fire();

        VBox box = new VBox(10, btnRefresh, chart);
        box.setPadding(new Insets(10));
        return new Tab(cfg.title, box);
    }

    private VBox createStatusCard(MetricConfig cfg) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-border-color: #ccc; -fx-background-color: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 5, 0, 0, 0);");
        card.setPrefWidth(220);

        Label title = new Label(cfg.title);
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        Label status = new Label("---");
        status.setStyle("-fx-background-color: #f8f9fa; -fx-padding: 5;");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setAlignment(Pos.CENTER);

        Label reason = new Label("Нет прогноза");
        reason.setWrapText(true);
        reason.setFont(Font.font(10));
        reason.setTextFill(Color.GRAY);

        statusLabels.put(cfg.dbKey, status);
        reasonLabels.put(cfg.dbKey, reason);

        card.getChildren().addAll(title, status, reason);
        return card;
    }

    // --- CONFIG CLASS ---
    private static class MetricConfig {
        String title, dbKey, unit, rules;
        public MetricConfig(String t, String d, String u, String r) {
            title = t; dbKey = d; unit = u; rules = r;
        }
    }
}