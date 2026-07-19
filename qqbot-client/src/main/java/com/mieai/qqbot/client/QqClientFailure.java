package com.mieai.qqbot.client;

/** Stable failure categories shared by token and OpenAPI requests. */
public enum QqClientFailure {
    AUTHENTICATION,
    HTTP_STATUS,
    TIMEOUT,
    TRANSPORT,
    PROTOCOL
}
