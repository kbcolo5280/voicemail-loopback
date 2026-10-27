package com.voicemail.tracker;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Sends emails via a dynamically-built {@link JavaMailSenderImpl} that reads
 * credentials at send-time from {@link SmtpConfigStore}.
 *
 * Because the SMTP settings can be changed at runtime (no app restart needed),
 * we intentionally do NOT register JavaMailSender as a Spring bean — instead
 * we build a fresh sender on every call so changes take effect immediately.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private SmtpConfigStore configStore;

    // ── Internal sender factory ───────────────────────────────────────────────

    private JavaMailSenderImpl buildSender() {
        SmtpConfig c = configStore.get();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(c.getHost());
        sender.setPort(c.getPort());
        sender.setUsername(c.getUsername());
        sender.setPassword(c.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");

        switch (c.getEncryption()) {
            case "STARTTLS" -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.ssl.trust", c.getHost());
            }
            case "SSL" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.trust", c.getHost());
            }
            default -> {
                // NONE — plain SMTP, no TLS
                props.put("mail.smtp.starttls.enable", "false");
            }
        }

        // Connection timeouts (ms)
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout",           "10000");
        props.put("mail.smtp.writetimeout",      "10000");

        return sender;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends an HTML email with an optional CSV attachment.
     *
     * @param to                recipient email address
     * @param subject           email subject
     * @param htmlBody          HTML body content
     * @param csvAttachmentName file name for the attachment (null = no attachment)
     * @param csvContent        CSV content bytes (null = no attachment)
     */
    public void sendEmail(String to,
                          String subject,
                          String htmlBody,
                          String csvAttachmentName,
                          String csvContent) throws Exception {

        JavaMailSenderImpl sender = buildSender();
        SmtpConfig c = configStore.get();

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(new InternetAddress(c.effectiveFromEmail(), c.getFromName()));
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        if (csvAttachmentName != null && csvContent != null) {
            helper.addAttachment(
                    csvAttachmentName,
                    new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)),
                    "text/csv");
        }

        sender.send(message);
        log.info("[EmailService] Sent '{}' to {}", subject, to);
    }

    /**
     * Sends a quick smoke-test email so the admin can verify SMTP settings work.
     */
    public void sendTestEmail(String to) throws Exception {
        String html = """
                <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px;">
                  <h2 style="color:#1d4ed8;">✅ SMTP Test Successful</h2>
                  <p>Your email settings are working correctly.</p>
                  <p style="color:#6b7280;font-size:13px;">
                    Voicemail Tracker will use these settings to send scheduled reports.
                  </p>
                </div>
                """;
        sendEmail(to, "Voicemail Tracker — SMTP Test", html, null, null);
    }
}
