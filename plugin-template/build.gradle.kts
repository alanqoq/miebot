plugins {
    java
}

group = "com.example.qqbot"
version = providers.gradleProperty("pluginVersion").orElse("0.2.0").get()

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
    compileOnly("com.mieai.qqbot:qqbot-plugin-api:0.2.0")
    compileOnly("com.mieai.qqbot:qqbot-plugin-spi:0.2.0")
    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:0.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
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
            "Plugin-Requires" to "1.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send",
        )
    }
}
