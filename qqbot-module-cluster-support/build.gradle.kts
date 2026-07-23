description = "Multi-instance leases, fencing, artifact consistency, and shared state module"

dependencies {
    api(project(":qqbot-module-spi"))
    implementation(project(":qqbot-module-database-support"))
    implementation(project(":qqbot-module-qqbot-runtime"))
    implementation(project(":qqbot-module-plugin-support"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
}
