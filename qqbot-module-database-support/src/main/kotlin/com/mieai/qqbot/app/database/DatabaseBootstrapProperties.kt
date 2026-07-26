package com.mieai.qqbot.app.database

import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.database")
class DatabaseBootstrapProperties {
    var type: DatabaseType = DatabaseType.SQLITE
    var configFile: Path = Path.of("database-config.json")
    var candidateConfigFile: Path = Path.of("database-config.json.candidate")
    val sqlite = Sqlite()
    val mysql = Server.mysql()
    val postgresql = Server.postgresql()

    class Sqlite {
        var path: Path = Path.of("qqbot.db")
        var busyTimeout: Duration = Duration.ofSeconds(5)
    }

    class Server private constructor(initialPort: Int) {
        var host: String = "localhost"
        var port: Int = initialPort
        var databaseName: String = "qqbot"
        var username: String = "qqbot"
        var password: String = ""
        var passwordFile: Path? = null
        var sslMode: DatabaseSslMode = DatabaseSslMode.PREFERRED
        var connectTimeout: Duration = Duration.ofSeconds(5)

        companion object {
            fun mysql(): Server = Server(3306)

            fun postgresql(): Server = Server(5432)
        }
    }
}
