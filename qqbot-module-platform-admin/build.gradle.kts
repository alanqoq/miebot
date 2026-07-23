description = "Platform administration, authentication, onboarding, and Web shell module"

val moduleWebDist = layout.projectDirectory.dir(
    "../qqbot-admin-web/dist/modules/platform-admin/browser")

dependencies {
    api(project(":qqbot-module-spi"))
    api(project(":qqbot-admin-api"))
    implementation(project(":qqbot-module-database-support"))
    implementation(project(":qqbot-module-qqbot-runtime"))
    implementation(project(":qqbot-persistence"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.processResources {
    inputs.file(moduleWebDist.file("main.js"))
        .withPropertyName("moduleWebEntrypoint")
    from(moduleWebDist) {
        into("META-INF/qqbot/modules/platform-admin/web")
    }
}
