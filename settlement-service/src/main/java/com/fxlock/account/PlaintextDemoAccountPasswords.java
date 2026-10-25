package com.fxlock.account;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Intentionally unsafe, disposable local demonstration only; instantiated by the explicit demo profile. */
final class PlaintextDemoAccountPasswords implements AccountPasswords {
    private static final Pattern LEGACY_BCRYPT = Pattern.compile("\\$2[abxy]?\\$[0-9]{2}\\$[./A-Za-z0-9]{53}");

    @Override public void requireEnabled() {}

    @Override
    public String forStorage(String submitted) {
        if (submitted == null || isLegacyHash(submitted.toString())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DEMO_PASSWORD_FORMAT_UNSUPPORTED");
        }
        return submitted.toString();
    }

    @Override
    public boolean matches(String submitted, String stored) {
        if (isLegacyHash(stored)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_PASSWORD_RESET_REQUIRED");
        }
        return submitted != null && stored != null && stored.contentEquals(submitted);
    }

    private static boolean isLegacyHash(String value) {
        return value != null && LEGACY_BCRYPT.matcher(value).matches();
    }
}
