description = "QQ WebSocket Gateway state machine and transport"

dependencies {
    api(project(":qqbot-domain"))
    implementation(project(":qqbot-protocol"))
    implementation(project(":qqbot-client"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
