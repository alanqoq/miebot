package com.mieai.qqbot.admin.system;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final DataSource dataSource;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Clock clock;

    @Autowired
    public SystemController(
            DataSource dataSource,
            ObjectProvider<BuildProperties> buildProperties) {
        this(dataSource, buildProperties, Clock.systemUTC());
    }

    SystemController(
            DataSource dataSource,
            ObjectProvider<BuildProperties> buildProperties,
            Clock clock) {
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
        this.clock = clock;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        BuildProperties build = buildProperties.getIfAvailable();
        return new SystemInfoResponse(
                "qqbot-platform",
                build == null ? "development" : build.getVersion(),
                "RUNNING",
                databaseProduct(),
                Instant.now(clock));
    }

    private String databaseProduct() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception ignored) {
            return "Unavailable";
        }
    }
}
