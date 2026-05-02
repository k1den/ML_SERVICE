package org.k1den.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabasePool {
    private static HikariDataSource dataSource;

    static {
        String clickhouseUrl = org.k1den.util.ConfigLoader.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(clickhouseUrl);
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(3000);
        config.setIdleTimeout(600000);

        dataSource = new HikariDataSource(config);
    }

    private DatabasePool() {
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}