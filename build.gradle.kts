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
    version = "0.3.0"
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
val pluginSdkModules = listOf(
    "qqbot-domain",
    "qqbot-plugin-api", "qqbot-plugin-spi", "qqbot-plugin-testkit")
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
                    versionMapping {
                        usage("java-api") { fromResolutionOf("runtimeClasspath") }
                        usage("java-runtime") { fromResolutionResult() }
                    }
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

val moduleSdkModules = listOf("qqbot-module-api", "qqbot-module-spi")
val cleanModuleSdkRepository by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("module-sdk/repository"))
}
moduleSdkModules.forEach { moduleName ->
    project(":$moduleName") {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("moduleSdk") {
                    from(components["java"])
                    versionMapping {
                        usage("java-api") { fromResolutionOf("runtimeClasspath") }
                        usage("java-runtime") { fromResolutionResult() }
                    }
                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "QQBot framework module SDK")
                    }
                }
            }
            repositories {
                maven {
                    name = "moduleSdk"
                    url = uri(rootProject.layout.buildDirectory.dir("module-sdk/repository"))
                }
            }
        }
        tasks.matching { it.name == "publishModuleSdkPublicationToModuleSdkRepository" }.configureEach {
            dependsOn(cleanModuleSdkRepository)
        }
    }
}

val moduleSdkRepository by tasks.registering {
    group = "distribution"
    description = "Publishes the framework module API and SPI to a local SDK repository."
    dependsOn(moduleSdkModules.map { ":$it:publishModuleSdkPublicationToModuleSdkRepository" })
}

val moduleSdkDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the framework module SDK and development guide."
    dependsOn(moduleSdkRepository)
    archiveBaseName.set("qqbot-module-sdk")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("module-sdk/repository")) { into("repository") }
    from("MODULE_DEVELOPMENT.md")
}

val defaultModuleProjects = listOf(
    "qqbot-module-platform-admin",
    "qqbot-module-database-support",
    "qqbot-module-qqbot-runtime",
    "qqbot-module-plugin-support",
    "qqbot-module-operations",
    "qqbot-module-cluster-support",
    "qqbot-module-onebot11")

val defaultModuleDirectory by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Collects the default external framework module JARs."
    into(layout.buildDirectory.dir("runtime/modules"))
    defaultModuleProjects.forEach { moduleName ->
        val moduleJar = project(":$moduleName").tasks.named<Jar>("jar")
        dependsOn(moduleJar)
        from(moduleJar.flatMap { it.archiveFile })
    }
}

val stageDefaultModules by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies default module JARs into the project modules directory for Compose."
    dependsOn(defaultModuleDirectory)
    from(layout.buildDirectory.dir("runtime/modules"))
    into(layout.projectDirectory.dir("modules"))
}

val stageExamplePlugin by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the example robot plugin into the project plugins directory."
    val pluginJar = project(":qqbot-plugin-example").tasks.named<Jar>("jar")
    dependsOn(pluginJar)
    from(pluginJar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("plugins"))
}

val stageRuntimeExtensions by tasks.registering {
    group = "distribution"
    description = "Stages default framework modules and the example robot plugin for Compose."
    dependsOn(stageDefaultModules, stageExamplePlugin)
}

val defaultModuleDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the default /modules directory used by Debian Docker deployments."
    dependsOn(defaultModuleDirectory)
    archiveBaseName.set("qqbot-default-modules")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("runtime/modules")) { into("modules") }
    from("MODULE_DEVELOPMENT.md")
    from("ONEBOT11.md")
}
