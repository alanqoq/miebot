package com.mieai.qqbot.protocol

import java.nio.charset.StandardCharsets

internal object FixtureLoader {
    fun load(resourcePath: String): String {
        val input = FixtureLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Fixture not found: $resourcePath")
        return input.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
