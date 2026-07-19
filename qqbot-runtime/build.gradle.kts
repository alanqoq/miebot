description = "Multi-bot supervisor and isolated bot runtimes"

dependencies {
    api(project(":qqbot-domain"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-gateway"))
    implementation(project(":qqbot-plugin-spi"))
    implementation(project(":qqbot-plugin-host"))
    implementation(project(":qqbot-persistence"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
