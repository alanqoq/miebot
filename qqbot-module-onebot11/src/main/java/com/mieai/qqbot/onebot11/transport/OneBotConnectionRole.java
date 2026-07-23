package com.mieai.qqbot.onebot11.transport;

enum OneBotConnectionRole {
    API,
    EVENT,
    UNIVERSAL;

    boolean acceptsActions() {
        return this == API || this == UNIVERSAL;
    }

    boolean acceptsEvents() {
        return this == EVENT || this == UNIVERSAL;
    }
}
