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
    @GetMapping("/") String home(Model model,Principal principal) {
        model.addAttribute("demo",environment.acceptsProfiles(Profiles.of("demo")));
        model.addAttribute("integrated",environment.acceptsProfiles(Profiles.of("team","oracle-local")));
        String dashboard=environment.getProperty("fxlock.dashboard-url","http://localhost:8083");
        model.addAttribute("dashboardUrl",dashboard+"/dashboard/");
        model.addAttribute("historyUrl",dashboard+"/dashboard/history/");
        model.addAttribute("customer",principal.getName());
        var settings=config.current();
        model.addAttribute("minAmount",settings.minAmount()); model.addAttribute("maxAmount",settings.maxAmount());
        model.addAttribute("spreadBps",settings.spreadBps()); return "home";
    }
}
