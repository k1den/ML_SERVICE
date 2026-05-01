package org.k1den.util;

import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (input == null) {
                System.err.println("ВНИМАНИЕ: Файл application.yml не найден. Будут использованы значения по умолчанию.");
            } else {
                properties.load(input);
            }
        } catch (Exception ex) {
            System.err.println("Ошибка при чтении application.yml: " + ex.getMessage());
        }
    }

    public static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
}