plugins {
    id("org.springframework.boot")
}

val defaultModuleProjects = listOf(
    project(":qqbot-module-platform-admin"),
    project(":qqbot-module-database-support"),
    project(":qqbot-module-qqbot-runtime"),
    project(":qqbot-module-plugin-support"),
    project(":qqbot-module-operations"),
    project(":qqbot-module-cluster-support"),
    project(":qqbot-module-onebot11")
)

val prepareTestModules by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("test-modules"))
    defaultModuleProjects.forEach { moduleProject ->
        val moduleJar = moduleProject.tasks.named<Jar>("jar")
        dependsOn(moduleJar)
        from(moduleJar.flatMap { it.archiveFile })
    }
}

val externalTestModuleClasspath = fileTree(layout.buildDirectory.dir("test-modules")) {
    include("*.jar")
}

description = "Spring Boot application and executable distribution"

dependencies {
    implementation(project(":qqbot-module-host"))
    implementation(project(":qqbot-admin-api"))
    implementation(project(":qqbot-client"))
    implementation(project(":qqbot-gateway"))
    implementation(project(":qqbot-runtime"))
    implementation(project(":qqbot-persistence"))
    implementation(project(":qqbot-plugin-host"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.xerial:sqlite-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly(project(":qqbot-module-platform-admin"))
}

springBoot {
    mainClass = "com.mieai.qqbot.app.QqBotApplication"
    buildInfo()
}

tasks.bootJar {
    manifest {
        attributes["Main-Class"] = "org.springframework.boot.loader.launch.PropertiesLauncher"
        attributes["Loader-Path"] = "modules"
    }
}

val frontendDist = layout.projectDirectory.dir("../qqbot-admin-web/dist/qqbot-admin-web/browser")

tasks.processResources {
    from(frontendDist) {
        into("static")
    }
}

tasks.test {
    dependsOn(prepareTestModules)
    classpath += externalTestModuleClasspath
    systemProperty(
        "qqbot.modules.directory",
        layout.buildDirectory.dir("test-modules").get().asFile.absolutePath)
}
