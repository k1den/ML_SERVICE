package org.k1den;

import org.k1den.daemon.PredictionDaemon;
import org.k1den.ui.MonitoringApp;

public class Launcher {
    public static void main(String[] args) {
        System.out.println("Инициализация системы мониторинга...");

        Thread backendThread = new Thread(() -> {
            try {
                System.out.println("Старт фонового демона аналитики...");
                PredictionDaemon.main(new String[]{});
            } catch (Exception e) {
                System.err.println("Критическая ошибка демона: " + e.getMessage());
            }
        });

        backendThread.setDaemon(true);
        backendThread.start();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        System.out.println("Запуск графического интерфейса...");
        MonitoringApp.main(args);
    }
}