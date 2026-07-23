description = "Trusted example plugin demonstrating the stable SPI"

dependencies {
    compileOnly(project(":qqbot-plugin-api"))
    compileOnly(project(":qqbot-plugin-spi"))
}

tasks.jar {
    archiveBaseName.set("qqbot-plugin-echo")
    manifest {
        attributes(
            "Plugin-Id" to "echo",
            "Plugin-Name" to "Echo Reply",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "2.0.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "qqbot-plugin-default.json",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send,storage",
        )
    }
}
