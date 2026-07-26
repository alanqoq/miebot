description = "PF4J plugin discovery, validation, and isolated execution"

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":qqbot-plugin-spi"))
    implementation(project(":qqbot-runtime"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-persistence"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.pf4j:pf4j:3.13.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.mockito:mockito-junit-jupiter")
}

tasks.test {
    dependsOn(":qqbot-plugin-example:jar")
    val exampleJar = project(":qqbot-plugin-example").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    systemProperty("qqbot.example.plugin", exampleJar.get().asFile.absolutePath)
}
