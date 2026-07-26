package com.mieai.qqbot.runtime.security

interface KeyProvider {
    fun activeKey(): KeyMaterial

    fun findById(keyId: String): KeyMaterial?
}
