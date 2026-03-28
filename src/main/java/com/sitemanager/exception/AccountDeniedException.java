package com.sitemanager.exception;

public class AccountDeniedException extends RuntimeException {
    public AccountDeniedException() {
        super("Account registration was denied");
    }
}
