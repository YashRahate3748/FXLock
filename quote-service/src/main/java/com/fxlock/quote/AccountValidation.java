package com.fxlock.quote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

@Component
public class AccountValidation {
    private final Validator validator;

    public AccountValidation(Validator validator) {
        this.validator = validator;
    }

    record EmailInput(@NotBlank @Email @Size(max = 200) String email) {}

    static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    boolean validEmail(String email) {
        return validator.validate(new EmailInput(email)).isEmpty() && bytes(email) <= 200;
    }

    static boolean validPassword(String password) {
        return password != null && !password.isBlank() && bytes(password) <= 72;
    }

    List<String> registrationErrors(String fullName, String email, String password, String confirmation) {
        var errors = new ArrayList<String>();
        if (fullName.isBlank() || fullName.length() > 150 || bytes(fullName) > 150) {
            errors.add("Enter a full name of at most 150 UTF-8 bytes.");
        }
        if (!validEmail(email)) {
            errors.add("Enter a valid email address of at most 200 UTF-8 bytes.");
        }
        if (!validPassword(password)) {
            errors.add("Enter a nonblank password of at most 72 UTF-8 bytes. Spaces are preserved.");
        }
        if (password == null || !password.equals(confirmation)) {
            errors.add("Passwords must match.");
        }
        return errors;
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
