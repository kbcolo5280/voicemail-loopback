package com.voicemail.tracker;

/**
 * Holds the SMTP settings a tenant administrator configures so that
 * scheduled voicemail reports are sent from their own email environment.
 *
 * Stored in-memory by {@link SmtpConfigStore}.  On server restart the
 * settings are cleared; for persistence they should be written to a
 * database or environment variables.
 */
public class SmtpConfig {

    private String host        = "";
    private int    port        = 587;
    /** NONE | STARTTLS | SSL */
    private String encryption  = "STARTTLS";
    private String username    = "";
    private String password    = "";
    private String fromName    = "Voicemail Tracker";
    /** Defaults to username if blank */
    private String fromEmail   = "";

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getHost()                   { return host; }
    public void   setHost(String v)           { this.host = v == null ? "" : v.trim(); }

    public int    getPort()                   { return port; }
    public void   setPort(int v)              { this.port = v; }

    public String getEncryption()             { return encryption; }
    public void   setEncryption(String v)     { this.encryption = v == null ? "STARTTLS" : v; }

    public String getUsername()               { return username; }
    public void   setUsername(String v)       { this.username = v == null ? "" : v.trim(); }

    public String getPassword()               { return password; }
    public void   setPassword(String v)       { this.password = v == null ? "" : v; }

    public String getFromName()               { return fromName; }
    public void   setFromName(String v)       { this.fromName = v == null ? "Voicemail Tracker" : v.trim(); }

    public String getFromEmail()              { return fromEmail; }
    public void   setFromEmail(String v)      { this.fromEmail = v == null ? "" : v.trim(); }

    /** Returns the effective "From" address: fromEmail if set, otherwise username. */
    public String effectiveFromEmail() {
        return (fromEmail == null || fromEmail.isBlank()) ? username : fromEmail;
    }
}
