package com.fxlock.account;

interface AccountPasswords {
    void requireEnabled();
    String forStorage(String submitted);
    boolean matches(String submitted, String stored);
}
