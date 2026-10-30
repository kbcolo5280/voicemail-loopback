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
 * Runs every minute to check which email subscriptions are due and fires them.
 *
 * Schedule logic:
 *  - DAILY  : fires once per day at the HH:mm matching scheduleTime in the subscription's timezone
 *  - WEEKLY : fires once per week on scheduleDay at the HH:mm in the subscription's timezone
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

    /** Tracks the LocalDate (in each subscription's own timezone) when last sent. */
    private final ConcurrentHashMap<String, LocalDate> lastSentDate = new ConcurrentHashMap<>();

    @Scheduled(cron = "0 * * * * *")   // every minute
    public void runScheduled() {
        if (!smtpConfigStore.isConfigured()) return;

        String sessionId = tokenStore.getAnyActiveSessionId();
        if (sessionId == null) {
            log.info("[SubscriptionScheduler] No active or refreshable RC session — skipping run");
            return;
        }

        log.info("[SubscriptionScheduler] Checking subscriptions at server time={}Z", LocalTime.now(ZoneOffset.UTC));

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

    public void runSubscription(SubscriptionRecord sub, String sessionId) throws Exception {
        ZoneId zone  = resolveZone(sub);
        LocalDate today = LocalDate.now(zone);

        int days = switch (sub.getPeriod()) {
            case "7d"  -> 7;
            case "30d" -> 30;
            case "60d" -> 60;
            case "90d" -> 90;
            default    -> 30;
        };
        String dateFrom = today.minusDays(days).toString();
        String dateTo   = today.toString();

        List<VoicemailRecord> records;
        if ("queues".equals(sub.getViewMode())) {
            records = new java.util.ArrayList<>(voicemailService.fetchAllQueueVoicemails(sessionId, null, dateFrom, dateTo));
        } else if ("user".equals(sub.getViewMode())) {
            records = new java.util.ArrayList<>(voicemailService.fetchAllUserVoicemails(sessionId, null, dateFrom, dateTo));
        } else {
            List<VoicemailRecord> users  = voicemailService.fetchAllUserVoicemails(sessionId, null, dateFrom, dateTo);
            List<VoicemailRecord> queues = voicemailService.fetchAllQueueVoicemails(sessionId, null, dateFrom, dateTo);
            records = new java.util.ArrayList<>(users);
            records.addAll(queues);
        }

        java.util.List<String> selUsers  = sub.getSelectedUserIds();
        java.util.List<String> selQueues = sub.getSelectedQueueIds();
        if (selUsers  != null && !selUsers.isEmpty()) {
            java.util.Set<String> userSet = new java.util.HashSet<>(selUsers);
            records.removeIf(r -> "User".equals(r.getExtensionType())
                    && !userSet.contains(r.getExtensionId()));
        }
        if (selQueues != null && !selQueues.isEmpty()) {
            java.util.Set<String> queueSet = new java.util.HashSet<>(selQueues);
            records.removeIf(r -> "Department".equals(r.getExtensionType())
                    && !queueSet.contains(r.getExtensionId()));
        }

        records.sort((a, b) -> {
            String da = a.getDateTime() != null ? a.getDateTime() : "";
            String db = b.getDateTime() != null ? b.getDateTime() : "";
            return db.compareTo(da);
        });

        String subject     = sub.getName() + " — Voicemail Report (" + sub.getPeriod() + ")";
        String htmlBody    = reportService.generateHtmlEmail(records, sub.getPeriod(), sub.getViewMode());
        String csvContent  = reportService.generateCsv(records);
        String csvFilename = "voicemail-report-" + today + ".csv";

        for (String recipient : sub.getRecipients()) {
            if (recipient == null || recipient.isBlank()) continue;
            emailService.sendEmail(recipient.trim(), subject, htmlBody, csvFilename, csvContent);
        }

        lastSentDate.put(sub.getId(), today);
        log.info("[SubscriptionScheduler] '{}' sent to {} recipient(s), {} voicemails (tz={})",
                sub.getName(), sub.getRecipients().size(), records.size(), zone);
    }

    private boolean isDue(SubscriptionRecord sub) {
        int scheduledHour   = 8;
        int scheduledMinute = 0;
        if (sub.getScheduleTime() != null && sub.getScheduleTime().contains(":")) {
            try {
                String[] parts  = sub.getScheduleTime().split(":");
                scheduledHour   = Integer.parseInt(parts[0]);
                scheduledMinute = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) { }
        }

        // Use the subscription's timezone — NOT the server's UTC clock
        ZoneId    zone  = resolveZone(sub);
        LocalTime now   = LocalTime.now(zone);
        LocalDate today = LocalDate.now(zone);

        if (now.getHour() != scheduledHour || now.getMinute() != scheduledMinute) return false;

        if ("WEEKLY".equalsIgnoreCase(sub.getSchedule())) {
            String todayAbbr = today.getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    .toUpperCase(Locale.ENGLISH);
            if (!todayAbbr.equals(sub.getScheduleDay())) return false;
        }

        LocalDate last = lastSentDate.get(sub.getId());
        return last == null || !last.equals(today);
    }

    private ZoneId resolveZone(SubscriptionRecord sub) {
        try {
            String tz = sub.getTimezone();
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz);
        } catch (Exception ignored) { }
        return ZoneOffset.UTC;
    }
}
