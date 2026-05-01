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
import java.util.concurrent.TimeUnit;

public class PredictionDaemon {

    private static final String TOPIC = org.k1den.util.ConfigLoader.getProperty("kafka.topic.features", "features_topic");
    private static final int HISTORY_WINDOW_SIZE = 30;

    private static final Cache<String, Map<String, LinkedList<Double>>> metricsHistory = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS)
            .build();

    public static void main(String[] args) {
        System.out.println("Запуск Universal Prediction Daemon...");

        ObjectMapper mapper = new ObjectMapper();
        ClickHouseWriter dbWriter = new ClickHouseWriter();
        MathEngine mathEngine = new MathEngine();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, org.k1den.util.ConfigLoader.getProperty("kafka.bootstrap.servers", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, org.k1den.util.ConfigLoader.getProperty("kafka.group.id", "prediction-service-group"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));
            System.out.println("Подписались на топик: " + TOPIC);

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        DeviceFeature feature = mapper.readValue(record.value(), DeviceFeature.class);

                        processMetric(feature.deviceId, "cpuLoad", feature.avgCpuLoad, feature.windowEndTimestamp, mathEngine, dbWriter);
                        processMetric(feature.deviceId, "memoryUsedPercent", feature.maxMemoryUsed, feature.windowEndTimestamp, mathEngine, dbWriter);
                        processMetric(feature.deviceId, "cpuTemperature", feature.avgCpuTemp, feature.windowEndTimestamp, mathEngine, dbWriter);
                        processMetric(feature.deviceId, "networkRxBytes", feature.avgNetRx, feature.windowEndTimestamp, mathEngine, dbWriter);
                        processMetric(feature.deviceId, "networkTxBytes", feature.avgNetTx, feature.windowEndTimestamp, mathEngine, dbWriter);
                        processMetric(feature.deviceId, "processCount", feature.avgProcesses, feature.windowEndTimestamp, mathEngine, dbWriter);

                        if (feature.disksUsedPercents != null) {
                            for (Map.Entry<String, Double> disk : feature.disksUsedPercents.entrySet()) {
                                String metricName = "DISK:" + disk.getKey();
                                processMetric(feature.deviceId, metricName, disk.getValue(), feature.windowEndTimestamp, mathEngine, dbWriter);
                            }
                        }

                    } catch (Exception e) {
                        System.err.println("Ошибка обработки сообщения: " + e.getMessage());
                    }
                }
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