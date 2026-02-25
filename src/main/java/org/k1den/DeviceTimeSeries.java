package org.k1den;

import java.util.List;

public class DeviceTimeSeries {
    public String deviceId;
    public List<Double> cpuLoadHistory;
    public List<Long> timestamps;

    // !!! ОБЯЗАТЕЛЬНО: Пустой конструктор для Flink POJO Serializer !!!
    public DeviceTimeSeries() {}

    public DeviceTimeSeries(String deviceId, List<Double> cpuLoadHistory, List<Long> timestamps) {
        this.deviceId = deviceId;
        this.cpuLoadHistory = cpuLoadHistory;
        this.timestamps = timestamps;
    }

    // Для удобства отладки можно добавить toString
    @Override
    public String toString() {
        return "Device: " + deviceId + ", points: " + (cpuLoadHistory != null ? cpuLoadHistory.size() : 0);
    }
}