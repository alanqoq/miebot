description = "Portable SQL persistence, migrations, Inbox, and Outbox"

dependencies {
    api(project(":qqbot-domain"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.flywaydb:flyway-core")
    implementation("org.xerial:sqlite-jdbc")

    testImplementation("com.zaxxer:HikariCP")
    testImplementation("org.xerial:sqlite-jdbc")
}
