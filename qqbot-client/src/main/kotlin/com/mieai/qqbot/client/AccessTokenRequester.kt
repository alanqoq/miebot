package com.mieai.qqbot.client

import java.util.concurrent.CompletionStage

/** Asynchronously obtains an app access token for one bot. */
fun interface AccessTokenRequester {
    fun requestToken(credentials: BotCredentials): CompletionStage<AccessToken>
}
