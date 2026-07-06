package org.mdpnp.headless.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
    private final HikariDataSource dataSource;

    public ConnectionPool(String host, int port, String database, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, database));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(5000);
        config.setLeakDetectionThreshold(10000);
        config.setAutoCommit(true);
        config.setPoolName("timescale-pool");
        config.setConnectionTestQuery("SELECT 1");

        try {
            dataSource = new HikariDataSource(config);
            log.info("Connection pool created for jdbc:postgresql://{}:{}/{}", host, port, database);
        } catch (Exception e) {
            log.warn("Failed to create TimescaleDB connection pool: {}", e.getMessage());
            log.warn("Database persistence will be disabled.");
            dataSource = null;
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database pool is not available.");
        }
        return dataSource.getConnection();
    }

    public boolean isAvailable() {
        return dataSource != null;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Connection pool closed.");
        }
    }
}
