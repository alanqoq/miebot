pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mirai-qqbot"

include(
    "qqbot-domain",
    "qqbot-protocol",
    "qqbot-client",
    "qqbot-gateway",
    "qqbot-runtime",
    "qqbot-plugin-api",
    "qqbot-plugin-spi",
    "qqbot-plugin-host",
    "qqbot-plugin-testkit",
    "qqbot-plugin-example",
    "qqbot-persistence",
    "qqbot-admin-api",
    "qqbot-app",
)
