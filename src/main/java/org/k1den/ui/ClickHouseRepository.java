package org.k1den.ui;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClickHouseRepository {

    private final String url = "jdbc:clickhouse://localhost:8123/default";

    // 1. Узнаем имя хоста и устройства (для заголовка)
    public String getDeviceInfo(String deviceId) {
        String sql = "SELECT deviceName, hostname FROM device_metrics WHERE deviceId = ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("deviceName") + " (" + rs.getString("hostname") + ")";
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Unknown Device";
    }

    // 2. Узнаем, какие диски есть у этого устройства (чтобы создать вкладки)
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

    // 3. Универсальный метод для получения данных
    public List<MetricPoint> getLastMetrics(String deviceId, String metricName, int limit) {
        List<MetricPoint> points = new ArrayList<>();
        String sql;

        // ЛОГИКА: Если метрика начинается с "DISK:", значит лезем в таблицу disk_metrics
        if (metricName.startsWith("DISK:")) {
            String mountPoint = metricName.substring(5); // Отрезаем "DISK:"
            sql = "SELECT timestamp, usedPercent as val FROM disk_metrics " +
                    "WHERE deviceId = ? AND mountPoint = ? ORDER BY timestamp DESC LIMIT ?";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setString(2, mountPoint);
                ps.setInt(3, limit);
                executeAndParse(ps, points);
            } catch (SQLException e) { e.printStackTrace(); }

        } else {
            // Иначе лезем в device_metrics
            sql = "SELECT timestamp, " + metricName + " as val FROM device_metrics " +
                    "WHERE deviceId = ? ORDER BY timestamp DESC LIMIT ?";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setInt(2, limit);
                executeAndParse(ps, points);
            } catch (SQLException e) {
                System.err.println("Нет колонки " + metricName);
            }
        }

        Collections.reverse(points);
        return points;
    }

    private void executeAndParse(PreparedStatement ps, List<MetricPoint> points) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
            }
        }
    }

    public List<MetricPoint> getMetricsForLastMinutes(String deviceId, String metricName, int minutesBack) {
        List<MetricPoint> points = new ArrayList<>();
        String sql;

        // Вычисляем timestamp (в миллисекундах), который был X минут назад
        long timeThreshold = System.currentTimeMillis() - ((long) minutesBack * 60 * 1000);

        if (metricName.startsWith("DISK:")) {
            String mountPoint = metricName.substring(5);
            sql = "SELECT timestamp, usedPercent as val FROM disk_metrics " +
                    "WHERE deviceId = ? AND mountPoint = ? AND timestamp >= ? ORDER BY timestamp ASC";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setString(2, mountPoint);
                ps.setLong(3, timeThreshold);
                executeAndParseAsc(ps, points);
            } catch (SQLException e) { e.printStackTrace(); }

        } else {
            sql = "SELECT timestamp, " + metricName + " as val FROM device_metrics " +
                    "WHERE deviceId = ? AND timestamp >= ? ORDER BY timestamp ASC";

            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deviceId);
                ps.setLong(2, timeThreshold);
                executeAndParseAsc(ps, points);
            } catch (SQLException e) {
                System.err.println("Нет колонки " + metricName);
            }
        }

        return points;
    }

    // Вспомогательный метод. Сортировка ASC сразу выдает правильный порядок (от старых к новым)
    private void executeAndParseAsc(PreparedStatement ps, List<MetricPoint> points) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                points.add(new MetricPoint(rs.getLong("timestamp"), rs.getDouble("val")));
            }
        }
    }

    // Метод для получения списка всех уникальных устройств из БД
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

        // Если база пустая, отдаем заглушку, чтобы интерфейс не сломался
        if (devices.isEmpty()) {
            devices.add("device-001");
        }
        return devices;
    }
}