description = "OneBot 11 WebSocket compatibility module for QQ C2C and group bots"

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

val moduleWebDist = layout.projectDirectory.dir(
    "../qqbot-admin-web/dist/modules/onebot11/browser")

dependencies {
    api(project(":qqbot-module-spi"))
    implementation(project(":qqbot-module-database-support"))
    implementation(project(":qqbot-module-qqbot-runtime"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-gateway"))
    implementation(project(":qqbot-protocol"))
    implementation(project(":qqbot-runtime"))
    implementation(project(":qqbot-domain"))
    implementation(project(":qqbot-persistence"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.xerial:sqlite-jdbc")
}

tasks.processResources {
    inputs.file(moduleWebDist.file("main.js"))
        .withPropertyName("moduleWebEntrypoint")
    from(moduleWebDist) {
        into("META-INF/qqbot/modules/onebot11/web")
    }
}
