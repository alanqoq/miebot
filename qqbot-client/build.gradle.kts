description = "Reusable Access Token and QQ OpenAPI client"

dependencies {
    api(project(":qqbot-domain"))
    api(project(":qqbot-protocol"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
