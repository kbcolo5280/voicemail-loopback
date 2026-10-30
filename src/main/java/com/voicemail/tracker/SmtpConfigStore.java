package com.voicemail.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Singleton holding the tenant's SMTP configuration.
 * Persists to /data/smtp.json so settings survive server restarts.
 */
@Component
public class SmtpConfigStore {

    private static final Logger log       = LoggerFactory.getLogger(SmtpConfigStore.class);
    private static final String DATA_DIR  = "/data";
    private static final String DATA_FILE = "/data/smtp.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile SmtpConfig config = new SmtpConfig();

    // ── Load on startup ───────────────────────────────────────────────────────

    @PostConstruct
    public void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) {
            log.info("[SmtpConfigStore] No existing config file — starting fresh");
            return;
        }
        try {
            config = mapper.readValue(f, SmtpConfig.class);
            log.info("[SmtpConfigStore] Loaded SMTP config from disk (host={})", config.getHost());
        } catch (Exception e) {
            log.error("[SmtpConfigStore] Failed to load from disk: {}", e.getMessage());
        }
    }

    // ── Persist after every mutation ──────────────────────────────────────────

    private void persist() {
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(DATA_FILE), config);
        } catch (Exception e) {
            log.error("[SmtpConfigStore] Failed to persist to disk: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the current SMTP configuration (never null). */
    public SmtpConfig get() {
        return config;
    }

    /** Replaces the stored configuration and persists to disk. */
    public void save(SmtpConfig c) {
        this.config = (c != null) ? c : new SmtpConfig();
        persist();
    }

    /**
     * Returns {@code true} when the minimum required SMTP fields (host,
     * username and password) have been filled in.
     */
    public boolean isConfigured() {
        SmtpConfig c = config;
        return c != null
                && c.getHost()     != null && !c.getHost().isBlank()
                && c.getUsername() != null && !c.getUsername().isBlank()
                && c.getPassword() != null && !c.getPassword().isBlank();
    }
}
