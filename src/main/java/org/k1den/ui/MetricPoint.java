package org.k1den.ui;

public class MetricPoint {
    public long timestamp; // Unix time
    public double value;   // Значение (CPU или RAM)

    public MetricPoint(long timestamp, double value) {
        this.timestamp = timestamp;
        this.value = value;
    }
}