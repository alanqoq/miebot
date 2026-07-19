package com.mieai.qqbot.app.database;

import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.database")
public class DatabaseBootstrapProperties {
    private DatabaseType type = DatabaseType.SQLITE;
    private Path configFile = Path.of("database-config.json");
    private Path candidateConfigFile = Path.of("database-config.json.candidate");
    private final Sqlite sqlite = new Sqlite();
    private final Server mysql = Server.mysql();
    private final Server postgresql = Server.postgresql();

    public DatabaseType getType() {
        return type;
    }

    public void setType(DatabaseType type) {
        this.type = type;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public void setConfigFile(Path configFile) {
        this.configFile = configFile;
    }

    public Path getCandidateConfigFile() {
        return candidateConfigFile;
    }

    public void setCandidateConfigFile(Path candidateConfigFile) {
        this.candidateConfigFile = candidateConfigFile;
    }

    public Sqlite getSqlite() {
        return sqlite;
    }

    public Server getMysql() {
        return mysql;
    }

    public Server getPostgresql() {
        return postgresql;
    }

    public static final class Sqlite {
        private Path path = Path.of("qqbot.db");
        private Duration busyTimeout = Duration.ofSeconds(5);

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }

        public Duration getBusyTimeout() {
            return busyTimeout;
        }

        public void setBusyTimeout(Duration busyTimeout) {
            this.busyTimeout = busyTimeout;
        }
    }

    public static final class Server {
        private String host = "localhost";
        private int port;
        private String databaseName = "qqbot";
        private String username = "qqbot";
        private String password = "";
        private Path passwordFile;
        private DatabaseSslMode sslMode = DatabaseSslMode.PREFERRED;
        private Duration connectTimeout = Duration.ofSeconds(5);

        private static Server mysql() {
            Server server = new Server();
            server.port = 3306;
            return server;
        }

        private static Server postgresql() {
            Server server = new Server();
            server.port = 5432;
            return server;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Path getPasswordFile() {
            return passwordFile;
        }

        public void setPasswordFile(Path passwordFile) {
            this.passwordFile = passwordFile;
        }

        public DatabaseSslMode getSslMode() {
            return sslMode;
        }

        public void setSslMode(DatabaseSslMode sslMode) {
            this.sslMode = sslMode;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
    }
}
