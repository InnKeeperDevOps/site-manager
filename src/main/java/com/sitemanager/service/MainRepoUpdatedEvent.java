package com.sitemanager.service;

import org.springframework.context.ApplicationEvent;

/**
 * Published when the main-repo is re-cloned after a settings change,
 * signaling that active suggestion repos should merge with the new main.
 */
public class MainRepoUpdatedEvent extends ApplicationEvent {

    private final String repoUrl;

    public MainRepoUpdatedEvent(Object source, String repoUrl) {
        super(source);
        this.repoUrl = repoUrl;
    }

    public String getRepoUrl() {
        return repoUrl;
    }
}
