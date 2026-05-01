package org.k1den.model;

public class PredictionPoint {
    public long timestamp;
    public double value;

    public PredictionPoint(long timestamp, double value) {
        this.timestamp = timestamp;
        this.value = value;
    }
}