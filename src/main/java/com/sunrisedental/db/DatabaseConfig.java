package com.sunrisedental.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Properties;

/**
 * Holds a single shared HikariCP connection pool for the application.
 * Values come from environment variables first, falling back to
 * db.properties on the classpath (gitignored, local-dev only).
 */
public final class DatabaseConfig {

    private static final HikariDataSource DATA_SOURCE = build();

    private DatabaseConfig() {
    }

    private static HikariDataSource build() {
        Properties fileProps = new Properties();
        try (InputStream in = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            if (in != null) {
                fileProps.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read db.properties", e);
        }

        String host = value("DB_HOST", fileProps, "db.host", "localhost");
        String port = value("DB_PORT", fileProps, "db.port", "3306");
        String name = value("DB_NAME", fileProps, "db.name", null);
        String user = value("DB_USER", fileProps, "db.user", null);
        String password = value("DB_PASSWORD", fileProps, "db.password", null);

        if (name == null || user == null) {
            throw new IllegalStateException(
                    "Database name and user must be set via DB_NAME/DB_USER env vars or db.properties");
        }

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(1_800_000);
        config.setPoolName("sunrise-dental-pool");

        return new HikariDataSource(config);
    }

    private static String value(String envKey, Properties fileProps, String propKey, String fallback) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromFile = fileProps.getProperty(propKey);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        return fallback;
    }

    public static Connection getConnection() throws java.sql.SQLException {
        return DATA_SOURCE.getConnection();
    }
}
