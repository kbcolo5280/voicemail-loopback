package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for reading, saving, and testing the tenant's SMTP configuration.
 *
 * Endpoints:
 *   GET  /api/smtp/config       → returns config (password masked)
 *   POST /api/smtp/config       → saves new config
 *   POST /api/smtp/test         → sends a test email to the specified address
 */
@RestController
@RequestMapping("/api/smtp")
public class SmtpConfigController {

    @Autowired
    private SmtpConfigStore store;

    @Autowired
    private EmailService emailService;

    // ── GET config ───────────────────────────────────────────────────────────

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        SmtpConfig c = store.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host",        c.getHost());
        out.put("port",        c.getPort());
        out.put("encryption",  c.getEncryption());
        out.put("username",    c.getUsername());
        out.put("fromName",    c.getFromName());
        out.put("fromEmail",   c.getFromEmail());
        out.put("hasPassword", c.getPassword() != null && !c.getPassword().isBlank());
        out.put("configured",  store.isConfigured());
        return out;
    }

    // ── POST config ──────────────────────────────────────────────────────────

    @PostMapping("/config")
    public Map<String, String> saveConfig(@RequestBody SmtpConfig incoming) {
        // If the incoming password is blank or the sentinel "••••••••",
        // keep the existing password so the UI doesn't wipe it on re-save.
        if (incoming.getPassword() == null
                || incoming.getPassword().isBlank()
                || incoming.getPassword().equals("••••••••")) {
            incoming.setPassword(store.get().getPassword());
        }
        store.save(incoming);
        return Map.of("status", "saved");
    }

    // ── POST test ────────────────────────────────────────────────────────────

    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, String> body) {
        if (!store.isConfigured()) {
            return Map.of("success", false, "message", "SMTP is not configured yet.");
        }
        String to = body.getOrDefault("email", store.get().getUsername());
        if (to == null || to.isBlank()) {
            return Map.of("success", false, "message", "Provide a recipient email address.");
        }
        try {
            emailService.sendTestEmail(to);
            return Map.of("success", true, "message", "Test email sent to " + to);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage() != null
                    ? e.getMessage() : "Unknown error");
        }
    }
}
