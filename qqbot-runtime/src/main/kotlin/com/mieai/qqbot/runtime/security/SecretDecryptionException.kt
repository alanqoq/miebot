package com.mieai.qqbot.runtime.security
class SecretDecryptionException(cause: Throwable) : RuntimeException("Unable to decrypt AppSecret", cause)
