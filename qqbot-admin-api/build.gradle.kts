description = "Shared administration error contracts and HTTP exception handling"

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":qqbot-runtime"))
    implementation(project(":qqbot-persistence"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.slf4j:slf4j-api")
}
