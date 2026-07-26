package com.mieai.qqbot.admin.security

/** Deliberately indistinguishable from an invalid login credential at the API boundary. */
class InvalidCurrentPasswordException : RuntimeException("The current password is invalid")
