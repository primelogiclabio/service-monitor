package dev.svcmon.hub.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite data source. The data directory is created before the first connection
 * is opened, which a plain spring.datasource.url cannot do.
 */
@Configuration
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(HubProperties props) {
        Path dir = props.dataDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dir, e);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("svcmon-sqlite");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dir.resolve("svcmon.db")
                + "?journal_mode=WAL&busy_timeout=10000&foreign_keys=on&synchronous=NORMAL");
        cfg.setMaximumPoolSize(4);
        return new HikariDataSource(cfg);
    }
}
