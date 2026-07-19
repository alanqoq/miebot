description = "Plugin capability fakes and reusable contract tests"

dependencies {
    api(project(":qqbot-plugin-spi"))
    api("org.junit.jupiter:junit-jupiter-api")
    implementation("org.assertj:assertj-core")
}
