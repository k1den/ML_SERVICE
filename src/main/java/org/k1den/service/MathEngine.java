package org.k1den.service;

import org.k1den.model.PredictionPoint;

import java.util.ArrayList;
import java.util.List;

public class MathEngine {

    public static final long SUSTAINED_WARN_THRESHOLD_MS = 5 * 60 * 1000L; // 5 минут

    private static final double SUSTAINED_VALUE_THRESHOLD = 90.0;

    public static class PredictionResult {
        public List<PredictionPoint> points = new ArrayList<>();
        public String status = "OK";
        public String reason = "Норма";
    }

    /**
     * @param historyValues  история значений метрики
     * @param lastTimestamp  временная метка последнего значения (мс)
     * @param metricName     название метрики
     * @param forecastMinutes горизонт прогноза в минутах
     * @param sensitivity    чувствительность к аномалиям
     * @param sustainedMs    как долго (мс) значение уже непрерывно держится выше порога;
     *                       0 если превышения нет или оно только что началось
     */
    public PredictionResult predictPolynomial(List<Double> historyValues, long lastTimestamp,
                                              String metricName, int forecastMinutes,
                                              double sensitivity, long sustainedMs) {
        PredictionResult result = new PredictionResult();
        int n = historyValues.size();
        if (n < 3) return result;

        double sum = 0;
        for (double v : historyValues) sum += v;
        double avg = sum / n;

        double variance = 0;
        for (double v : historyValues) variance += Math.pow(v - avg, 2);
        double stdDev = Math.sqrt(variance / n);

        int half = n / 2;
        double sum1 = 0, sum2 = 0;
        for (int i = 0; i < half; i++) sum1 += historyValues.get(i);
        for (int i = half; i < n; i++) sum2 += historyValues.get(i);
        double avg1 = sum1 / half;
        double avg2 = sum2 / (n - half);

        double slope = (avg2 - avg1) / (n / 2.0);

        double maxSlope = Math.max(0.05, Math.abs(avg * 0.01));
        slope = Math.max(-maxSlope, Math.min(maxSlope, slope));

        boolean isPercentage = metricName.toLowerCase().contains("load")
                || metricName.toLowerCase().contains("percent")
                || metricName.startsWith("DISK:");
        boolean isTemp = metricName.toLowerCase().contains("temp");

        long stepMs = 20_000;
        int futurePoints = forecastMinutes * 3;

        double[] deviations = new double[n];

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - 2);
            double smaSum = 0;
            for (int j = start; j <= i; j++) smaSum += historyValues.get(j);
            double sma = smaSum / (i - start + 1);

            deviations[i] = historyValues.get(i) - sma;

            long ts = lastTimestamp - ((n - 1 - i) * stepMs);
            result.points.add(new PredictionPoint(ts, sma));
        }

        double lastActualY = historyValues.get(n - 1);
        double trendBaseY = lastActualY;
        double lastValidY = lastActualY;

        double safeSlope = slope * 0.5;

        for (int i = 1; i <= futurePoints; i++) {
            double trendY = trendBaseY + (safeSlope * i);

            double pullStrength = (double) i / futurePoints;
            trendY = trendY * (1.0 - pullStrength) + avg * pullStrength;

            int patternLength = Math.min(15, n);
            int deterministicOffset = Math.abs((int) ((i * 31L + lastTimestamp) % patternLength));
            int patternIndex = (n - patternLength) + deterministicOffset;
            double historicalWiggle = deviations[patternIndex];

            double noiseDecay = 1.0 - (pullStrength * 0.7);
            double predictedY = trendY + (historicalWiggle * noiseDecay);

            predictedY = Math.max(0, predictedY);
            if (isPercentage) predictedY = Math.min(100, predictedY);

            long ts = lastTimestamp + stepMs * i;
            result.points.add(new PredictionPoint(ts, predictedY));
            lastValidY = predictedY;
        }

        if (isPercentage) {
            if (lastValidY > 90) {
                result.status = "ERROR";
                result.reason = "Критическая перегрузка (>90%)";
            } else if (lastValidY > 75) {
                result.status = "WARN";
                result.reason = "Повышенное потребление ресурса";
            } else if (sustainedMs >= SUSTAINED_WARN_THRESHOLD_MS) {
                long sustainedMinutes = sustainedMs / 60_000;
                result.status = "WARN";
                result.reason = "Длительная нагрузка >90% уже " + sustainedMinutes + " мин";
            } else {
                result.status = "OK";
                result.reason = "Норма";
            }
        } else if (isTemp) {
            if (lastValidY > 85) {
                result.status = "ERROR";
                result.reason = "Критический перегрев!";
            } else if (lastValidY > 70) {
                result.status = "WARN";
                result.reason = "Температура выше нормы";
            } else if (sustainedMs >= SUSTAINED_WARN_THRESHOLD_MS && historyValues.get(n - 1) > 70) {
                long sustainedMinutes = sustainedMs / 60_000;
                result.status = "WARN";
                result.reason = "Повышенная температура держится " + sustainedMinutes + " мин";
            } else {
                result.status = "OK";
                result.reason = "Норма";
            }
        } else {
            if (lastValidY > avg * 1.5 && lastValidY > avg + (stdDev * sensitivity) && lastValidY > 50) {
                result.status = "WARN";
                result.reason = "Аномальный рост значения";
            } else {
                result.status = "OK";
                result.reason = "Норма";
            }
        }

        return result;
    }
}