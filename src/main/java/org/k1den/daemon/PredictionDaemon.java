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

    private static final Cache<String, Long> sustainedAnomalyStart = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS)
            .build();

    private static final double SUSTAINED_TRACK_THRESHOLD = 90.0;
    private static final double SUSTAINED_TEMP_THRESHOLD  = 85.0;

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
        metrics.put("cpuLoad", feature.avgCpuLoad);
        metrics.put("memoryUsedPercent", feature.maxMemoryUsed);
        metrics.put("cpuTemperature", feature.avgCpuTemp);
        metrics.put("networkRxBytes", feature.avgNetRx);
        metrics.put("networkTxBytes", feature.avgNetTx);
        metrics.put("processCount", feature.avgProcesses);

        if (feature.disksUsedPercents != null) {
            feature.disksUsedPercents.forEach((mp, val) ->
                    metrics.put("DISK:" + mp, val));
        }

        double[] settings = dbWriter.getSettings();
        int forecastMinutes = (int) settings[0];
        double sensitivity = settings[1];

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String metricName = entry.getKey();
            double currentValue = entry.getValue();

            Map<String, LinkedList<Double>> deviceData =
                    metricsHistory.get(feature.deviceId, k -> new HashMap<>());
            deviceData.putIfAbsent(metricName, new LinkedList<>());
            LinkedList<Double> history = deviceData.get(metricName);
            history.add(currentValue);
            if (history.size() > HISTORY_WINDOW_SIZE) history.removeFirst();

            long sustainedMs = updateSustainedTracker(
                    feature.deviceId, metricName, currentValue, feature.windowEndTimestamp);

            if (history.size() >= 10) {
                List<Double> historySnapshot = new ArrayList<>(history);
                final long finalSustainedMs = sustainedMs;

                writerPool.submit(() -> {
                    try {
                        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                                historySnapshot, feature.windowEndTimestamp,
                                metricName, forecastMinutes, sensitivity, finalSustainedMs);
                        dbWriter.savePrediction(feature.deviceId, metricName,
                                result.points, result.status, result.reason);
                    } catch (Exception e) {
                        System.err.println("Ошибка записи прогноза [" + metricName + "]: " + e.getMessage());
                    }
                });
            }
        }
    }

    private static long updateSustainedTracker(String deviceId, String metricName,
                                               double currentValue, long eventTimestampMs) {
        String key = deviceId + "::" + metricName;

        boolean isAboveThreshold = isAboveAnomalyThreshold(metricName, currentValue);

        if (isAboveThreshold) {
            Long firstSeenAt = null;
            try {
                firstSeenAt = sustainedAnomalyStart.getIfPresent(key);
            } catch (Exception ignored) {}

            if (firstSeenAt == null) {
                sustainedAnomalyStart.put(key, eventTimestampMs);
                return 0L;
            } else {
                return Math.max(0, eventTimestampMs - firstSeenAt);
            }
        } else {
            sustainedAnomalyStart.invalidate(key);
            return 0L;
        }
    }

    private static boolean isAboveAnomalyThreshold(String metricName, double value) {
        boolean isTemp = metricName.toLowerCase().contains("temp");
        if (isTemp) {
            return value >= SUSTAINED_TEMP_THRESHOLD;
        }

        boolean isPercentage = metricName.toLowerCase().contains("load")
                || metricName.toLowerCase().contains("percent")
                || metricName.startsWith("DISK:");
        if (isPercentage) {
            return value >= SUSTAINED_TRACK_THRESHOLD;
        }

        return false;
    }
}