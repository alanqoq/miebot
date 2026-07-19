import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}
allprojects {
    group = "com.mieai.qqbot"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16")
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// The SDK is consumed by plugin projects, so publish a self-contained local
// Maven repository in addition to the normal Gradle module outputs.
val pluginSdkModules = listOf("qqbot-domain", "qqbot-plugin-api", "qqbot-plugin-spi", "qqbot-plugin-testkit")
val cleanPluginSdkRepository by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("plugin-sdk/repository"))
}
pluginSdkModules.forEach { moduleName ->
    project(":$moduleName") {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("sdk") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "QQBot plugin SDK module")
                    }
                }
            }
            repositories {
                maven {
                    name = "sdk"
                    url = uri(rootProject.layout.buildDirectory.dir("plugin-sdk/repository"))
                }
            }
        }
        tasks.matching { it.name == "publishSdkPublicationToSdkRepository" }.configureEach {
            dependsOn(cleanPluginSdkRepository)
        }
    }
}

val pluginSdkRepository by tasks.registering {
    group = "distribution"
    description = "Publishes the plugin API, SPI, testkit and domain jars to a local SDK repository."
    dependsOn(pluginSdkModules.map { ":$it:publishSdkPublicationToSdkRepository" })
}

val pluginSdkDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds a copyable plugin SDK repository, template and development guide."
    dependsOn(pluginSdkRepository)
    archiveBaseName.set("qqbot-plugin-sdk")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("plugin-sdk/repository")) { into("repository") }
    from("plugin-template") {
        into("plugin-template")
        exclude(".gradle/**", "build/**")
    }
    from("PLUGIN_DEVELOPMENT.md")
    from("README.md") { into("project") }
}
