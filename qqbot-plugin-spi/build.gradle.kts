description = "Plugin entrypoint, lifecycle, and event handler contracts"

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":qqbot-domain"))
    api(project(":qqbot-plugin-api"))
}
