plugins {
    id("org.springframework.boot")
}

description = "Spring Boot application and executable distribution"

dependencies {
    implementation(project(":qqbot-admin-api"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-gateway"))
    implementation(project(":qqbot-runtime"))
    implementation(project(":qqbot-plugin-host"))
    implementation(project(":qqbot-persistence"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.xerial:sqlite-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

springBoot {
    mainClass = "com.mieai.qqbot.app.QqBotApplication"
    buildInfo()
}

val frontendDist = layout.projectDirectory.dir("../qqbot-admin-web/dist/qqbot-admin-web/browser")

tasks.processResources {
    from(frontendDist) {
        into("static")
    }
}
