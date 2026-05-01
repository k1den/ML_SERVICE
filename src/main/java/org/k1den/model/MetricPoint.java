package org.k1den.model;

public class MetricPoint {
    public long timestamp;
    public double value;

    public MetricPoint(long timestamp, double value) {
        this.timestamp = timestamp;
        this.value = value;
    }
}