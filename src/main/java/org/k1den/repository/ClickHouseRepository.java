package org.k1den.repository;

import org.k1den.model.MetricPoint;
import org.k1den.util.DatabasePool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ClickHouseRepository {

    private final String url = org.k1den.util.ConfigLoader.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");

    public List<String> getDiskMountPoints(String deviceId) {
        List<String> disks = new ArrayList<>();
        String sql = "SELECT DISTINCT mountPoint FROM disk_metrics WHERE deviceId = ?";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    disks.add(rs.getString("mountPoint"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return disks;
    }

    public List<MetricPoint> getMetricsForLastMinutes(String deviceId, String metricName, int minutesBack) {
        List<MetricPoint> points = new ArrayList<>();
        long timeThreshold = System.currentTimeMillis() - ((long) minutesBack * 60 * 1000);

        if (metricName.startsWith("DISK:")) {
            String mountPoint = metricName.substring(5);
            String sql = "SELECT timestamp, usedPercent as val FROM disk_metrics " +
                    "WHERE deviceId = ? AND mountPoint = ? AND timestamp >= ? ORDER BY timestamp ASC";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setString(2, mountPoint);
                ps.setLong(3, timeThreshold);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Ошибка чтения истории для диска " + mountPoint + ": " + e.getMessage());
            }
        } else {
            String dbColumn = metricName;
            switch (metricName) {
                case "cpuLoad":
                    dbColumn = "avgCpuLoad";
                    break;
                case "memoryUsedPercent":
                    dbColumn = "maxMemoryUsed";
                    break;
                case "cpuTemperature":
                    dbColumn = "avgCpuTemp";
                    break;
                case "processCount":
                    dbColumn = "avgProcesses";
                    break;
                case "networkRxBytes":
                    dbColumn = "avgNetRx";
                    break;
                case "networkTxBytes":
                    dbColumn = "avgNetTx";
                    break;
            }

            String sql = "SELECT timestamp, " + dbColumn + " as val FROM metrics_features " +
                    "WHERE deviceId = ? AND timestamp >= ? ORDER BY timestamp ASC";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setLong(2, timeThreshold);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Ошибка чтения истории для колонки: " + dbColumn + " - " + e.getMessage());
            }
        }

        return points;
    }

    public List<String> getAvailableDevices() {
        List<String> devices = new ArrayList<>();
        String sql = "SELECT DISTINCT deviceId FROM device_metrics ORDER BY deviceId";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                devices.add(rs.getString("deviceId"));
            }
        } catch (SQLException e) {
            System.err.println("Ошибка при получении списка устройств: " + e.getMessage());
        }

        if (devices.isEmpty()) {
            devices.add("device-001");
        }
        return devices;
    }

    public static class PredictionData {
        public List<MetricPoint> points = new ArrayList<>();
        public String status = "UNKNOWN";
        public String reason = "Ожидание данных от сервиса прогнозов...";
        public long createdAt = 0;
    }

    public PredictionData getLatestPrediction(String deviceId, String metricName) {
        PredictionData result = new PredictionData();

        String sqlMaxTimestamp = "SELECT MAX(createdAt) as maxTime FROM predictions WHERE deviceId = ? AND metricName = ?";
        long maxTime = 0;
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sqlMaxTimestamp)) {
            ps.setString(1, deviceId);
            ps.setString(2, metricName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) maxTime = rs.getLong("maxTime");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (maxTime == 0) return result;
        result.createdAt = maxTime;

        String sqlData = "SELECT forecastTime, predictedValue, status, reason FROM predictions " +
                "WHERE deviceId = ? AND metricName = ? AND createdAt = ? ORDER BY forecastTime ASC";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sqlData)) {
            ps.setString(1, deviceId);
            ps.setString(2, metricName);
            ps.setLong(3, maxTime);

            try (ResultSet rs = ps.executeQuery()) {
                boolean metaSet = false;
                while (rs.next()) {
                    if (!metaSet) {
                        result.status = rs.getString("status");
                        result.reason = rs.getString("reason");
                        metaSet = true;
                    }
                    result.points.add(new MetricPoint(rs.getLong("forecastTime"), rs.getDouble("predictedValue")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    public List<MetricPoint> getMetricsBetween(String deviceId, String metricName, long startMs, long endMs) {
        List<MetricPoint> points = new ArrayList<>();

        if (metricName.startsWith("DISK:")) {
            String mountPoint = metricName.substring(5);
            String sql = "SELECT timestamp, usedPercent as val FROM disk_metrics " +
                    "WHERE deviceId = ? AND mountPoint = ? AND timestamp >= ? AND timestamp <= ? ORDER BY timestamp ASC";
            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setString(2, mountPoint);
                ps.setLong(3, startMs);
                ps.setLong(4, endMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Ошибка чтения истории (TimeMachine) для диска " + mountPoint + ": " + e.getMessage());
            }
            return points;
        }

        String dbColumn = metricName;
        switch (metricName) {
            case "cpuLoad":
                dbColumn = "avgCpuLoad";
                break;
            case "memoryUsedPercent":
                dbColumn = "maxMemoryUsed";
                break;
            case "cpuTemperature":
                dbColumn = "avgCpuTemp";
                break;
            case "processCount":
                dbColumn = "avgProcesses";
                break;
            case "networkRxBytes":
                dbColumn = "avgNetRx";
                break;
            case "networkTxBytes":
                dbColumn = "avgNetTx";
                break;
        }

        String sql = "SELECT timestamp, " + dbColumn + " as val FROM metrics_features " +
                "WHERE deviceId = ? AND timestamp >= ? AND timestamp <= ? ORDER BY timestamp ASC";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setLong(2, startMs);
            ps.setLong(3, endMs);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка чтения истории (TimeMachine) для: " + dbColumn + " - " + e.getMessage());
        }

        return points;
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

    public void saveSettings(int forecastMinutes, double sensitivity) {
        String sql = "INSERT INTO prediction_settings (timestamp, forecastMinutes, anomalySensitivity) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, forecastMinutes);
            ps.setDouble(3, sensitivity);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getWorstStatusForDevice(String deviceId) {
        String[] metrics = {"cpuLoad", "memoryUsedPercent", "cpuTemperature", "processCount", "networkRxBytes", "networkTxBytes"};
        boolean hasWarn = false;
        for (String metric : metrics) {
            PredictionData pd = getLatestPrediction(deviceId, metric);
            if (pd.status != null) {
                String s = pd.status.toUpperCase();
                if (s.contains("ERROR") || s.contains("CRIT")) return "ERROR";
                if (s.contains("WARN")) hasWarn = true;
            }
        }
        return hasWarn ? "WARN" : "OK";
    }

    public double calculateMAE(String deviceId, String metricName, int hoursBack) {
        long timeThreshold = System.currentTimeMillis() - ((long) hoursBack * 3600 * 1000);

        if (metricName.startsWith("DISK:")) {
            String mountPoint = metricName.substring(5);
            String sql = "SELECT avg(abs(p.predictedValue - f.val)) as mae " +
                    "FROM predictions p ASOF INNER JOIN ( " +
                    "  SELECT deviceId, timestamp as ts, usedPercent as val " +
                    "  FROM disk_metrics " +
                    "  WHERE deviceId = ? AND mountPoint = ? AND timestamp >= ? " +
                    ") f ON p.deviceId = f.deviceId AND p.forecastTime >= f.ts " +
                    "WHERE p.deviceId = ? AND p.metricName = ? AND p.createdAt >= ?";
            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId); ps.setString(2, mountPoint); ps.setLong(3, timeThreshold);
                ps.setString(4, deviceId); ps.setString(5, metricName); ps.setLong(6, timeThreshold);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.wasNull() ? -1.0 : rs.getDouble("mae");
                }
            } catch (SQLException e) { System.err.println("Ошибка расчета MAE дисков: " + e.getMessage()); }
            return -1.0;
        }

        String dbColumn = metricName;
        switch (metricName) {
            case "cpuLoad": dbColumn = "avgCpuLoad"; break;
            case "memoryUsedPercent": dbColumn = "maxMemoryUsed"; break;
            case "cpuTemperature": dbColumn = "avgCpuTemp"; break;
            case "processCount": dbColumn = "avgProcesses"; break;
            case "networkRxBytes": dbColumn = "avgNetRx"; break;
            case "networkTxBytes": dbColumn = "avgNetTx"; break;
        }

        String sql = "SELECT avg(abs(p.predictedValue - f.val)) as mae " +
                "FROM predictions p " +
                "ASOF INNER JOIN ( " +
                "  SELECT deviceId, timestamp as ts, " + dbColumn + " as val " +
                "  FROM metrics_features " +
                "  WHERE deviceId = ? AND timestamp >= ? " +
                ") f ON p.deviceId = f.deviceId AND p.forecastTime >= f.ts " +
                "WHERE p.deviceId = ? AND p.metricName = ? AND p.createdAt >= ?";

        try (Connection conn = DatabasePool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setLong(2, timeThreshold);
            ps.setString(3, deviceId);
            ps.setString(4, metricName);
            ps.setLong(5, timeThreshold);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double mae = rs.getDouble("mae");
                    return rs.wasNull() ? -1.0 : mae;
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка расчета MAE: " + e.getMessage());
        }
        return -1.0;
    }
}