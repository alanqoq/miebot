package com.mieai.qqbot.onebot11.config

/** Runtime settings with the access token decrypted only for an enabled transport. */
data class ResolvedOneBot11Config(val config: OneBot11Config, val accessToken: String) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
    }
}
