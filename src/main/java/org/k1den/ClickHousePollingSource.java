package org.k1den;

import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.configuration.Configuration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class ClickHousePollingSource extends RichSourceFunction<DeviceTimeSeries> {

    private volatile boolean isRunning = true;
    private String clickhouseUrl;
    private long pollingIntervalMs;

    public ClickHousePollingSource(String url, long intervalMs) {
        this.clickhouseUrl = url;
        this.pollingIntervalMs = intervalMs;
    }

    @Override
    public void run(SourceContext<DeviceTimeSeries> ctx) throws Exception {
        while (isRunning) {
            try (Connection conn = DriverManager.getConnection(clickhouseUrl)) {
                // Забираем данные за последние 30 минут
                String sql = """
                    SELECT 
                        deviceId, 
                        groupArray(timestamp) as times,
                        groupArray(cpuLoad) as cpus
                    FROM (
                        SELECT deviceId, timestamp, cpuLoad 
                        FROM device_metrics 
                        WHERE timestamp >= toUInt64(now()) - 1800 
                        ORDER BY timestamp ASC
                    )
                    GROUP BY deviceId
                """;

                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        String deviceId = rs.getString("deviceId");

                        // --- ИСПРАВЛЕНИЕ ЗДЕСЬ ---

                        // 1. Извлекаем массивы как java.sql.Array
                        Array cpuSqlArray = rs.getArray("cpus");
                        Array timeSqlArray = rs.getArray("times");

                        // 2. Получаем сырой объект (это будет double[] и long[])
                        Object rawCpus = (cpuSqlArray != null) ? cpuSqlArray.getArray() : null;
                        Object rawTimes = (timeSqlArray != null) ? timeSqlArray.getArray() : null;

                        List<Double> cpuList = new ArrayList<>();
                        List<Long> timeList = new ArrayList<>();

                        // 3. Конвертируем double[] -> List<Double>
                        if (rawCpus instanceof double[]) {
                            for (double d : (double[]) rawCpus) {
                                cpuList.add(d);
                            }
                        } else if (rawCpus instanceof Double[]) {
                            // На случай, если драйвер вернет объекты
                            java.util.Collections.addAll(cpuList, (Double[]) rawCpus);
                        }

                        // 4. Конвертируем long[] -> List<Long>
                        if (rawTimes instanceof long[]) {
                            for (long l : (long[]) rawTimes) {
                                timeList.add(l);
                            }
                        } else if (rawTimes instanceof Long[]) {
                            java.util.Collections.addAll(timeList, (Long[]) rawTimes);
                        }

                        // Отправляем в Flink только если есть данные
                        if (!cpuList.isEmpty()) {
                            ctx.collect(new DeviceTimeSeries(
                                    deviceId,
                                    cpuList,
                                    timeList
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                // Логируем ошибку, но не роняем программу полностью, чтобы она попробовала снова
                System.err.println("Error reading from ClickHouse: " + e.getMessage());
                e.printStackTrace();
            }

            Thread.sleep(pollingIntervalMs);
        }
    }

    @Override
    public void cancel() {
        isRunning = false;
    }
}