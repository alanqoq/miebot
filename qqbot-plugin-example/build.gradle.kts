description = "Configurable keyword reply example plugin"

plugins {
    kotlin("jvm")
}

val embeddedLibraries by configurations.creating

configurations.compileOnly {
    extendsFrom(embeddedLibraries)
}

configurations.testRuntimeOnly {
    extendsFrom(embeddedLibraries)
}

dependencies {
    compileOnly(project(":qqbot-plugin-api"))
    compileOnly(project(":qqbot-plugin-spi"))
    embeddedLibraries("com.google.code.gson:gson:2.13.1")
    testImplementation(project(":qqbot-plugin-testkit"))
}

tasks.jar {
    archiveBaseName.set("qqbot-plugin-example")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({ embeddedLibraries.map(::zipTree) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
        )
    }
    manifest {
        attributes(
            "Plugin-Id" to "example",
            "Plugin-Name" to "example",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "3.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "config.json",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send",
        )
    }
}
