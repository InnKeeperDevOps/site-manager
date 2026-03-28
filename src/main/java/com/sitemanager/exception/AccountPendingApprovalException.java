package com.sitemanager.exception;

public class AccountPendingApprovalException extends RuntimeException {
    public AccountPendingApprovalException() {
        super("Account pending admin approval");
    }
}
