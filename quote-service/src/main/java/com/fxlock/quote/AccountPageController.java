package com.fxlock.quote;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountPageController {
    private final AccountClient accounts;
    private final AccountValidation validation;
    private final Environment environment;

    public AccountPageController(AccountClient accounts, AccountValidation validation, Environment environment) {
        this.accounts = accounts;
        this.validation = validation;
        this.environment = environment;
    }

    @GetMapping("/login")
    String login(@RequestParam(name = "demo", required = false) String demo, Model model) {
        model.addAttribute("demoLogin", demo != null || !persistentAccounts());
        model.addAttribute("loginAction", persistentAccounts() && demo != null ? "/demo-login" : "/login");
        model.addAttribute("persistentAccounts", persistentAccounts());
        return "login";
    }

    @GetMapping("/register")
    String registration(Model model) {
        model.addAttribute("persistentAccounts", persistentAccounts());
        return "register";
    }

    @PostMapping("/register")
    String register(@RequestParam(defaultValue = "") String fullName,
                    @RequestParam(defaultValue = "") String email,
                    @RequestParam(defaultValue = "") String password,
                    @RequestParam(defaultValue = "") String confirmPassword, Model model) {
        fullName = fullName.strip();
        email = AccountValidation.normalizeEmail(email);
        model.addAttribute("persistentAccounts", persistentAccounts());
        model.addAttribute("fullName", fullName);
        model.addAttribute("email", email);
        // Never bind credentials or a BindingResult containing rejected passwords into the model.
        var errors = validation.registrationErrors(fullName, email, password, confirmPassword);
        if (!persistentAccounts()) {
            errors.add("Account creation requires M2 team or oracle-local and the connected M3 demonstration profile.");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            return "register";
        }
        try {
            accounts.register(fullName, email, password);
            return "redirect:/login?registered";
        } catch (AccountClient.AccountFailure e) {
            model.addAttribute("errors", List.of(e.failure == AccountClient.Failure.CONFLICT
                ? "An account already exists for this email. Sign in instead."
                : "The account service is unavailable and account creation could not be confirmed. Try signing in before submitting again. This request was not automatically retried."));
            return "register";
        }
    }

    private boolean persistentAccounts() {
        return environment.acceptsProfiles(Profiles.of("team", "oracle-local"));
    }
}
