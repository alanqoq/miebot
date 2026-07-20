description = "Secured administration HTTP API, audit, and health endpoints"

dependencies {
    api(project(":qqbot-runtime"))
    implementation(project(":qqbot-domain"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-persistence"))
    implementation(project(":qqbot-plugin-host"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
