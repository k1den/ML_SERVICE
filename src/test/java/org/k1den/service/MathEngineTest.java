package org.k1den.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MathEngineTest {

    private MathEngine mathEngine;

    @BeforeEach
    void setUp() {
        mathEngine = new MathEngine();
    }

    @Test
    void testNormalCpuLoad_ReturnsOk() {
        List<Double> history = Arrays.asList(20.0, 21.0, 20.5, 22.0, 25.0, 24.0, 23.0);

        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                history, System.currentTimeMillis(), "avgCpuLoad", 15, 3.0, 0
        );

        assertEquals("OK", result.status, "Статус должен быть OK при нормальной нагрузке");
        assertEquals("Норма", result.reason);
        assertFalse(result.points.isEmpty(), "Вектор прогноза не должен быть пустым");
    }

    @Test
    void testAnomalousGrowth_ReturnsWarn() {
        List<Double> history = Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0, 100.0);

        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                history, System.currentTimeMillis(), "avgNetRx", 1, 1.0, 0
        );

        assertEquals("WARN", result.status, "Система должна выявить аномальный скачок трафика");
        assertTrue(result.reason.contains("Аномальный рост"), "Причина должна указывать на аномалию");
    }

    @Test
    void testCriticalTemperature_ReturnsError() {
        List<Double> history = Arrays.asList(99.0, 100.0, 101.0, 103.0, 102.0);

        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                history, System.currentTimeMillis(), "avgCpuTemp", 15, 3.0, 0
        );

        assertEquals("ERROR", result.status, "При температуре >= 100 статус должен быть ERROR");
        assertEquals("Критический перегрев!", result.reason);
    }

    @Test
    void testHighLoad_ReturnsWarn() {
        List<Double> history = Arrays.asList(88.0, 88.0, 88.0, 88.0);
        long sustainedMs = 6 * 60 * 1000L;

        MathEngine.PredictionResult result = mathEngine.predictPolynomial(
                history, System.currentTimeMillis(), "avgCpuLoad", 15, 3.0, sustainedMs
        );

        assertEquals("WARN", result.status, "Высокая длительная нагрузка (88%) должна вызывать WARN");
        assertNotNull(result.reason, "Причина не должна быть пустой");
    }
}