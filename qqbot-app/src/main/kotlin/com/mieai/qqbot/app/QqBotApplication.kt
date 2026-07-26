package com.mieai.qqbot.app

import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

@SpringBootConfiguration
@EnableAutoConfiguration
class QqBotApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(QqBotApplication::class.java, *args)
        }
    }
}
