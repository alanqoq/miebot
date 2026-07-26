package com.mieai.qqbot.runtime.supervisor
import java.util.concurrent.CompletionStage
interface ManagedBotRuntime { fun start():CompletionStage<Void>; fun stop():CompletionStage<Void> }
