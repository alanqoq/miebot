description = "Framework module dependency, lifecycle, service, and Web contribution host"

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    api(project(":qqbot-module-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webmvc")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
