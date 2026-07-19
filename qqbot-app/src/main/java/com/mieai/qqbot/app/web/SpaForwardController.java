package com.mieai.qqbot.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps Angular client-side routes working when the application serves the UI directly. */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/dashboard", "/bots", "/plugins", "/events", "/system", "/setup"})
    public String forwardAdminRoutes() {
        return "forward:/index.html";
    }
}
