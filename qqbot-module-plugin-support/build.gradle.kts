description = "Robot plugin SDK, loading, binding, delivery, upload, and administration module"

val moduleWebDist = layout.projectDirectory.dir(
    "../qqbot-admin-web/dist/modules/plugin-support/browser")

dependencies {
    api(project(":qqbot-module-spi"))
    api(project(":qqbot-plugin-api"))
    api(project(":qqbot-plugin-spi"))
    api(project(":qqbot-plugin-host"))
    implementation(project(":qqbot-module-qqbot-runtime"))
    implementation(project(":qqbot-module-database-support"))
    implementation(project(":qqbot-module-platform-admin"))
    implementation(project(":qqbot-admin-api"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-persistence"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":qqbot-module-platform-admin"))
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.processResources {
    inputs.file(moduleWebDist.file("main.js"))
        .withPropertyName("moduleWebEntrypoint")
    from(moduleWebDist) {
        into("META-INF/qqbot/modules/plugin-support/web")
    }
}
