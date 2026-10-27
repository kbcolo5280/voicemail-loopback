package com.voicemail.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs every hour to check which email subscriptions are due and fires them.
 *
 * Schedule logic:
 *  - DAILY  : fires once per day at the hour matching scheduleTime
 *  - WEEKLY : fires once per week on scheduleDay at the hour matching scheduleTime
 *
 * Because this runs in the cloud without an interactive user session, it
 * borrows any active RC session via {@link TokenStore#getAnyActiveSessionId()}.
 * If no one is logged in, the run is skipped — reports will fire on the next
 * scheduled tick after someone logs in.
 */
@Component
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    @Autowired private SubscriptionStore    subscriptionStore;
    @Autowired private VoicemailService     voicemailService;
    @Autowired private EmailService         emailService;
    @Autowired private ReportService        reportService;
    @Autowired private TokenStore           tokenStore;
    @Autowired private SmtpConfigStore      smtpConfigStore;

    /** Tracks the LocalDate on which each subscription was last successfully sent. */
    private final ConcurrentHashMap<String, LocalDate> lastSentDate = new ConcurrentHashMap<>();

    // ── Hourly trigger ────────────────────────────────────────────────────────

    @Scheduled(cron = "0 * * * * *")   // every minute
    public void runScheduled() {
        if (!smtpConfigStore.isConfigured()) return;

        String sessionId = tokenStore.getAnyActiveSessionId();
        if (sessionId == null) {
            log.info("[SubscriptionScheduler] No active RC session — skipping run");
            return;
        }

        int hour = LocalTime.now().getHour();
        log.info("[SubscriptionScheduler] Hourly check at hour={}", hour);

        for (SubscriptionRecord sub : subscriptionStore.getAll()) {
            if (!sub.isEnabled()) continue;
            if (isDue(sub)) {
                try {
                    runSubscription(sub, sessionId);
                } catch (Exception e) {
                    log.error("[SubscriptionScheduler] '{}' failed: {}", sub.getName(), e.getMessage());
                }
            }
        }
    }

    // ── Public run method (also called by the /send endpoint) ────────────────

    /**
     * Fetches voicemail data for the subscription's configured view and period,
     * generates the report, and sends it to all recipients.
     *
     * @param sub       the subscription to run
     * @param sessionId an active RC session ID to use for API calls
     */
    public void runSubscription(SubscriptionRecord sub, String sessionId) throws Exception {
        // Compute date range from period
        LocalDate today = LocalDate.now();
        int days = switch (sub.getPeriod()) {
            case "7d"  -> 7;
            case "30d" -> 30;
            case "60d" -> 60;
            case "90d" -> 90;
            default    -> 30;
        };
        String dateFrom = today.minusDays(days).toString();   // YYYY-MM-DD
        String dateTo   = today.toString();

        // Fetch voicemails
        List<VoicemailRecord> records;
        if ("queues".equals(sub.getViewMode())) {
            records = voicemailService.fetchAllQueueVoicemails(sessionId, null, dateFrom, dateTo);
        } else if ("user".equals(sub.getViewMode())) {
            records = voicemailService.fetchAllUserVoicemails(sessionId, null, dateFrom, dateTo);
        } else {
            // "both" — merge user + queue voicemails
            List<VoicemailRecord> users  = voicemailService.fetchAllUserVoicemails(sessionId, null, dateFrom, dateTo);
            List<VoicemailRecord> queues = voicemailService.fetchAllQueueVoicemails(sessionId, null, dateFrom, dateTo);
            records = new java.util.ArrayList<>(users);
            records.addAll(queues);
        }

        // Sort by dateTime descending
        records.sort((a, b) -> {
            String da = a.getDateTime() != null ? a.getDateTime() : "";
            String db = b.getDateTime() != null ? b.getDateTime() : "";
            return db.compareTo(da);
        });

        // Generate content
        String subject     = sub.getName() + " — Voicemail Report (" + sub.getPeriod() + ")";
        String htmlBody    = reportService.generateHtmlEmail(records, sub.getPeriod(), sub.getViewMode());
        String csvContent  = reportService.generateCsv(records);
        String csvFilename = "voicemail-report-" + today + ".csv";

        // Send to each recipient
        for (String recipient : sub.getRecipients()) {
            if (recipient == null || recipient.isBlank()) continue;
            emailService.sendEmail(recipient.trim(), subject, htmlBody, csvFilename, csvContent);
        }

        lastSentDate.put(sub.getId(), today);
        log.info("[SubscriptionScheduler] '{}' sent to {} recipient(s), {} voicemails",
                sub.getName(), sub.getRecipients().size(), records.size());
    }

    // ── Due check ─────────────────────────────────────────────────────────────

    private boolean isDue(SubscriptionRecord sub) {
        // Parse the configured hour and minute (HH:mm)
        int scheduledHour   = 8;
        int scheduledMinute = 0;
        if (sub.getScheduleTime() != null && sub.getScheduleTime().contains(":")) {
            try {
                String[] parts  = sub.getScheduleTime().split(":");
                scheduledHour   = Integer.parseInt(parts[0]);
                scheduledMinute = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) { }
        }

        LocalTime now = LocalTime.now();
        if (now.getHour() != scheduledHour || now.getMinute() != scheduledMinute) return false;

        LocalDate today = LocalDate.now();

        // For WEEKLY subscriptions, also check the day of week
        if ("WEEKLY".equalsIgnoreCase(sub.getSchedule())) {
            String todayAbbr = today.getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    .toUpperCase(Locale.ENGLISH);  // MON, TUE, …, SUN
            if (!todayAbbr.equals(sub.getScheduleDay())) return false;
        }

        // Check it hasn't already been sent today
        LocalDate last = lastSentDate.get(sub.getId());
        return last == null || !last.equals(today);
    }
}
