package com.mieai.qqbot.app.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Keeps Angular client-side routes working when the application serves the UI directly. */
@Controller
class SpaForwardController {
    @GetMapping(value = ["/", "/login", "/setup"])
    fun forwardAdminRoutes(): String = "forward:/index.html"

    @GetMapping("/modules/**")
    fun forwardModuleRoutes(): String = "forward:/index.html"
}
