import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.example.qqbot"
version = providers.gradleProperty("pluginVersion").orElse("1.0.8").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val sdkRepository = providers.gradleProperty("qqbotSdkRepository")
    .orElse(providers.environmentVariable("QQBOT_SDK_REPOSITORY"))
    .orElse("../build/plugin-sdk/repository")

repositories {
    maven { url = uri(sdkRepository.get()) }
    mavenCentral()
}

dependencies {
    // These are compile-only on purpose. The host supplies the SDK classes.
    compileOnly("com.mieai.qqbot:qqbot-plugin-api:1.0.8")
    compileOnly("com.mieai.qqbot:qqbot-plugin-spi:1.0.8")
    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:1.0.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("qqbot-plugin-template")
    manifest {
        attributes(
            "Plugin-Id" to "template",
            "Plugin-Name" to "QQBot Plugin Template",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "3.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "qqbot-plugin-default.json",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send",
        )
    }
}
