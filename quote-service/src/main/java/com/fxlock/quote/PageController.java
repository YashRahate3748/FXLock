package com.fxlock.quote;

import java.security.Principal;
import org.springframework.core.env.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    private final Environment environment;
    private final DatabaseConfig config;
    public PageController(Environment environment,DatabaseConfig config) { this.environment=environment; this.config=config; }
    @GetMapping("/transactions") String transactions(Model model,Principal principal) {
        model.addAttribute("customer",principal.getName());
        model.addAttribute("historyPage",true);
        return "transactions";
    }
    @GetMapping("/") String home(Model model,Principal principal) {
        model.addAttribute("demo",environment.acceptsProfiles(Profiles.of("demo","oracle-local")));
        model.addAttribute("customer",principal.getName());
        var settings=config.current();
        model.addAttribute("minAmount",settings.minAmount()); model.addAttribute("maxAmount",settings.maxAmount());
        model.addAttribute("spreadBps",settings.spreadBps()); return "home";
    }
}
