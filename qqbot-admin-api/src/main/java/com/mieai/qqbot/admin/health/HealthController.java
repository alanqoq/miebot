package com.mieai.qqbot.admin.health;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final DataSource dataSource;
    private final Clock clock;

    @Autowired
    public HealthController(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    HealthController(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @GetMapping("/live")
    public HealthStatus live() {
        return new HealthStatus("UP", Instant.now(clock), Map.of("process", "UP"));
    }

    @GetMapping("/ready")
    public ResponseEntity<HealthStatus> ready() {
        Instant checkedAt = Instant.now(clock);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1");
                ResultSet result = statement.executeQuery()) {
            if (result.next() && result.getInt(1) == 1) {
                return ResponseEntity.ok(new HealthStatus(
                        "UP", checkedAt, Map.of("database", "UP", "migrations", "UP")));
            }
        } catch (Exception ignored) {
            // Readiness deliberately returns a sanitized response; diagnostics stay in server logs.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthStatus("DOWN", checkedAt, Map.of("database", "DOWN")));
    }
}
