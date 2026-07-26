package com.mieai.qqbot.admin.database

import java.util.Arrays

/** Mutable database credential whose diagnostic representation never exposes its value. */
class DatabasePassword private constructor(value: CharArray) : AutoCloseable {
    private var value: CharArray? = value.clone()

    @Synchronized
    fun copyValue(): CharArray = value?.clone()
        ?: throw IllegalStateException("Database password has been destroyed")

    @get:Synchronized
    val isDestroyed: Boolean
        get() = value == null

    @Synchronized
    override fun close() {
        value?.let { Arrays.fill(it, '\u0000') }
        value = null
    }

    override fun toString(): String = "DatabasePassword[<redacted>]"

    companion object {
        fun of(value: String): DatabasePassword {
            val characters = value.toCharArray()
            try {
                return DatabasePassword(characters)
            } finally {
                Arrays.fill(characters, '\u0000')
            }
        }
    }
}
