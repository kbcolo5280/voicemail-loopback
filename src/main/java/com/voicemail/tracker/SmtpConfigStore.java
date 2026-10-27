package com.voicemail.tracker;

import org.springframework.stereotype.Component;

/**
 * In-memory singleton holding the tenant's SMTP configuration.
 * A single account shares one SMTP configuration (not per-session).
 *
 * NOTE: Lost on server restart.  For persistence, save to a database
 * or read initial values from environment / application.properties.
 */
@Component
public class SmtpConfigStore {

    private volatile SmtpConfig config = new SmtpConfig();

    /** Returns the current SMTP configuration (never null). */
    public SmtpConfig get() {
        return config;
    }

    /** Replaces the stored configuration. */
    public void save(SmtpConfig c) {
        this.config = (c != null) ? c : new SmtpConfig();
    }

    /**
     * Returns {@code true} when the minimum required SMTP fields (host and
     * username) have been filled in — i.e. the tenant has configured email.
     */
    public boolean isConfigured() {
        SmtpConfig c = config;
        return c != null
                && c.getHost()     != null && !c.getHost().isBlank()
                && c.getUsername() != null && !c.getUsername().isBlank()
                && c.getPassword() != null && !c.getPassword().isBlank();
    }
}
