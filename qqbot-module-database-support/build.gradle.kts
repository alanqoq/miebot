description = "Database providers, migrations, persistence, and live configuration module"

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    api(project(":qqbot-module-spi"))
    api(project(":qqbot-persistence"))
    compileOnly(project(":qqbot-module-host"))
    implementation(project(":qqbot-admin-api"))
    implementation(project(":qqbot-runtime"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework:spring-tx")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.slf4j:slf4j-api")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.25")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":qqbot-module-host"))
    testImplementation(project(":qqbot-module-platform-admin"))
    testImplementation("org.springframework.security:spring-security-test")
}
