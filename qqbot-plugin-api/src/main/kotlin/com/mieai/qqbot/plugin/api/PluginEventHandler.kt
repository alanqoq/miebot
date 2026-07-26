package com.mieai.qqbot.plugin.api

import java.util.concurrent.CompletionStage

fun interface PluginEventHandler {
    fun handle(event: PluginEvent): CompletionStage<Void>
}
