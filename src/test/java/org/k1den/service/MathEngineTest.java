package org.k1den.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MathEngineTest {

    @Test
    void testNormalCpuLoadPredictsOkStatus() {
        MathEngine engine = new MathEngine();
        List<Double> history = Arrays.asList(40.0, 42.0, 41.0, 40.0, 43.0, 41.0, 42.0, 40.0, 41.0, 42.0);
        long currentTimestamp = System.currentTimeMillis();

        MathEngine.PredictionResult result = engine.predictPolynomial(history, currentTimestamp, "cpuLoad", 15, 3.0);

        assertNotNull(result);
        assertEquals("OK", result.status, "Статус должен быть OK при нормальной загрузке");
        assertFalse(result.points.isEmpty(), "Алгоритм должен сгенерировать точки прогноза");
    }

    @Test
    void testSpikingCpuLoadPredictsErrorStatus() {
        MathEngine engine = new MathEngine();

        List<Double> history = Arrays.asList(88.0, 90.0, 92.0, 94.0, 95.0, 96.0, 97.0, 98.0, 99.0, 99.0);
        long currentTimestamp = System.currentTimeMillis();

        MathEngine.PredictionResult result = engine.predictPolynomial(history, currentTimestamp, "cpuLoad", 15, 3.0);

        assertEquals("ERROR", result.status, "Статус должен быть ERROR при критической перегрузке >90%");
    }
}