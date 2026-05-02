package org.k1den.repository;

import org.k1den.model.PredictionPoint;
import org.k1den.util.DatabasePool;

import java.sql.*;
import java.util.List;

public class ClickHouseWriter {
    private final String url = org.k1den.util.ConfigLoader.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");

    public ClickHouseWriter() {
        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute("""
                        CREATE TABLE IF NOT EXISTS predictions (
                            deviceId String, metricName String, createdAt UInt64, forecastTime UInt64, predictedValue Float64, status String, reason String
                        ) ENGINE = MergeTree() ORDER BY (deviceId, metricName, createdAt)
                    """);

            conn.createStatement().execute("""
                        CREATE TABLE IF NOT EXISTS prediction_settings (
                            timestamp UInt64, forecastMinutes Int32, anomalySensitivity Float64
                        ) ENGINE = MergeTree() ORDER BY timestamp
                    """);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT count() FROM prediction_settings")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    conn.createStatement().execute("INSERT INTO prediction_settings VALUES (" + System.currentTimeMillis() + ", 15, 3.0)");
                }
            }
            System.out.println("Таблицы ClickHouse готовы.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void savePrediction(String deviceId, String metricName, List<PredictionPoint> points, String status, String reason) {
        long createdAt = System.currentTimeMillis();
        String sql = "INSERT INTO predictions (deviceId, metricName, createdAt, forecastTime, predictedValue, status, reason) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabasePool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PredictionPoint point : points) {
                ps.setString(1, deviceId);
                ps.setString(2, metricName);
                ps.setLong(3, createdAt);
                ps.setLong(4, point.timestamp);
                ps.setDouble(5, point.value);
                ps.setString(6, status);
                ps.setString(7, reason);
                ps.addBatch();
            }
            ps.executeBatch();
            System.out.println("Сохранен прогноз для " + deviceId + " (" + metricName + "), статус: " + status);
        } catch (SQLException e) {
            System.err.println("Ошибка записи в ClickHouse: " + e.getMessage());
        }
    }

    public double[] getSettings() {
        double[] settings = {15.0, 3.0};
        String sql = "SELECT forecastMinutes, anomalySensitivity FROM prediction_settings ORDER BY timestamp DESC LIMIT 1";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                settings[0] = rs.getDouble("forecastMinutes");
                settings[1] = rs.getDouble("anomalySensitivity");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return settings;
    }
}