description = "Runtime dashboard, health, queue monitoring, and audit module"

val moduleWebDist = layout.projectDirectory.dir(
    "../qqbot-admin-web/dist/modules/operations/browser")

dependencies {
    api(project(":qqbot-module-spi"))
    implementation(project(":qqbot-module-database-support"))
    implementation(project(":qqbot-module-qqbot-runtime"))
    implementation(project(":qqbot-module-platform-admin"))
    implementation(project(":qqbot-module-plugin-support"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.security:spring-security-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.processResources {
    inputs.file(moduleWebDist.file("main.js"))
        .withPropertyName("moduleWebEntrypoint")
    from(moduleWebDist) {
        into("META-INF/qqbot/modules/operations/web")
    }
}
