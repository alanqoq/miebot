package com.mieai.qqbot.onebot11.config;

public final class OneBot11RevisionConflictException extends RuntimeException {
    public OneBot11RevisionConflictException() {
        super("OneBot settings revision changed");
    }
}
