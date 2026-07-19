package com.mieai.qqbot.admin.security;

/** Deliberately indistinguishable from an invalid login credential at the API boundary. */
public final class InvalidCurrentPasswordException extends RuntimeException {
    public InvalidCurrentPasswordException() {
        super("The current password is invalid");
    }
}
