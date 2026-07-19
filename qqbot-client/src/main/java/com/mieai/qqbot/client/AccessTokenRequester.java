package com.mieai.qqbot.client;

import java.util.concurrent.CompletionStage;

/** Asynchronously obtains an app access token for one bot. */
@FunctionalInterface
public interface AccessTokenRequester {
    CompletionStage<AccessToken> requestToken(BotCredentials credentials);
}
