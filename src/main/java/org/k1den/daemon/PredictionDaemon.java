package org.k1den.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.k1den.model.DeviceFeature;
import org.k1den.repository.ClickHouseWriter;
import org.k1den.service.MathEngine;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PredictionDaemon {

    private static final String TOPIC = org.k1den.util.ConfigLoader.getProperty("kafka.topic.features", "features_topic");
    private static final int HISTORY_WINDOW_SIZE = 30;

    private static final Cache<String, Map<String, LinkedList<Double>>> metricsHistory = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS)
            .build();
    private static final int WRITER_THREADS = 4;

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        ClickHouseWriter dbWriter = new ClickHouseWriter();
        MathEngine mathEngine = new MathEngine();

        ExecutorService writerPool = Executors.newFixedThreadPool(WRITER_THREADS);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, org.k1den.util.ConfigLoader.getProperty("kafka.bootstrap.servers", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, org.k1den.util.ConfigLoader.getProperty("kafka.group.id", "prediction-service-group"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                writerPool.shutdown();
                try {
                    if (!writerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                        writerPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    writerPool.shutdownNow();
                }
            }));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        DeviceFeature feature = mapper.readValue(record.value(), DeviceFeature.class);

                        processFeature(feature, mathEngine, dbWriter, writerPool);

                    } catch (Exception e) {
                        System.err.println("Ошибка обработки сообщения: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void processFeature(DeviceFeature feature, MathEngine mathEngine,
                                       ClickHouseWriter dbWriter, ExecutorService writerPool) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("cpuLoad",            feature.avgCpuLoad);
        metrics.put("memoryUsedPercent",  feature.maxMemoryUsed);
        metrics.put("cpuTemperature",     feature.avgCpuTemp);
        metrics.put("networkRxBytes",     feature.avgNetRx);
        metrics.put("networkTxBytes",     feature.avgNetTx);
        metrics.put("processCount",       feature.avgProcesses);

        if (feature.disksUsedPercents != null) {
            feature.disksUsedPercents.forEach((mp, val) ->
                    metrics.put("DISK:" + mp, val));
        }

        double[] settings = dbWriter.getSettings();
        int forecastMinutes = (int) settings[0];
        double sensitivity  = settings[1];

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String metricName  = entry.getKey();
            double currentValue = entry.getValue();

            Map<String, LinkedList<Double>> deviceData =
                    metricsHistory.get(feature.deviceId, k -> new HashMap<>());
            deviceData.putIfAbsent(metricName, new LinkedList<>());
            LinkedList<Double> history = deviceData.get(metricName);
            history.add(currentValue);
            if (history.size() > HISTORY_WINDOW_SIZE) history.removeFirst();

            if (history.size() >= 10) {
                List<Double> historySnapshot = new ArrayList<>(history);

                writerPool.submit(() -> {
                    try {
                        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                                historySnapshot, feature.windowEndTimestamp,
                                metricName, forecastMinutes, sensitivity);
                        dbWriter.savePrediction(feature.deviceId, metricName,
                                result.points, result.status, result.reason);
                    } catch (Exception e) {
                        System.err.println("Ошибка записи прогноза [" + metricName + "]: " + e.getMessage());
                    }
                });
            }
        }
    }

    private static void processMetric(String deviceId, String metricName, double currentValue, long timestamp, MathEngine mathEngine, ClickHouseWriter dbWriter) {
        Map<String, LinkedList<Double>> deviceData = metricsHistory.get(deviceId, k -> new HashMap<>());

        deviceData.putIfAbsent(metricName, new LinkedList<>());
        LinkedList<Double> history = deviceData.get(metricName);

        history.add(currentValue);
        if (history.size() > HISTORY_WINDOW_SIZE) {
            history.removeFirst();
        }

        if (history.size() >= 10) {
            double[] settings = dbWriter.getSettings();
            int forecastMinutes = (int) settings[0];
            double sensitivity = settings[1];

            MathEngine.PredictionResult result = mathEngine.predictPolynomial(history, timestamp, metricName, forecastMinutes, sensitivity);
            dbWriter.savePrediction(deviceId, metricName, result.points, result.status, result.reason);
        }
    }
}