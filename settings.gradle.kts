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
    "qqbot-module-api",
    "qqbot-module-spi",
    "qqbot-module-host",
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
    "qqbot-module-platform-admin",
    "qqbot-module-database-support",
    "qqbot-module-qqbot-runtime",
    "qqbot-module-plugin-support",
    "qqbot-module-operations",
    "qqbot-module-cluster-support",
    "qqbot-module-onebot11",
    "qqbot-app",
)
