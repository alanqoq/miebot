package com.mieai.qqbot.app.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.security.app-secret")
class AppSecretEncryptionProperties {
    var keyId: String = "primary"
    var masterKey: String = ""
    var masterKeyFile: String = ""
}
