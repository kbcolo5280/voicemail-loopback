package com.voicemail.tracker;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates report content (CSV attachment + HTML email body) from a list of
 * {@link VoicemailRecord} objects for inclusion in scheduled subscription emails.
 */
@Service
public class ReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    // ── CSV ──────────────────────────────────────────────────────────────────

    /**
     * Generates a CSV string from the given records.
     * The CSV includes a header row and one row per voicemail.
     */
    public String generateCsv(List<VoicemailRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status,Caller Name,Caller Number,Extension / Queue,Type," +
                  "Date & Time,Duration (sec),Called Back By,Callback Time\n");

        for (VoicemailRecord r : records) {
            sb.append(csv(r.getReadStatus())).append(',')
              .append(csv(r.getCallerName())).append(',')
              .append(csv(r.getCallerNumber())).append(',')
              .append(csv(r.getExtensionName())).append(',')
              .append(csv(r.getExtensionType())).append(',')
              .append(csv(r.getDateTime())).append(',')
              .append(r.getDuration()).append(',')
              .append(csv(r.getCallbackBy())).append(',')
              .append(csv(r.getCallbackTime())).append('\n');
        }
        return sb.toString();
    }

    // ── HTML email body ──────────────────────────────────────────────────────

    /**
     * Generates a styled HTML email body summarising the voicemail report.
     * The CSV is included as an attachment separately; this provides a
     * quick-glance summary in the email itself.
     */
    public String generateHtmlEmail(List<VoicemailRecord> records,
                                    String period,
                                    String viewMode) {

        long unread   = records.stream().filter(r -> "Unread".equals(r.getReadStatus())).count();
        long calledBack = records.stream().filter(r -> r.getCallbackBy() != null && !r.getCallbackBy().isEmpty()).count();

        String periodLabel = switch (period) {
            case "7d"  -> "Last 7 Days";
            case "30d" -> "Last 30 Days";
            case "60d" -> "Last 60 Days";
            case "90d" -> "Last 90 Days";
            default    -> period;
        };

        String viewLabel = switch (viewMode) {
            case "user"   -> "User Extensions";
            case "queues" -> "Call Queues";
            default       -> "All Extensions";
        };

        String today = LocalDate.now().format(FMT);

        // Build a preview table of up to 20 most-recent records
        StringBuilder tableRows = new StringBuilder();
        int previewCount = Math.min(records.size(), 20);
        List<VoicemailRecord> preview = records.subList(0, previewCount);

        for (VoicemailRecord r : preview) {
            String statusColor = "Unread".equals(r.getReadStatus()) ? "#dc2626" : "#16a34a";
            tableRows.append("<tr>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>")
                     .append("<span style='color:").append(statusColor).append(";font-weight:600;'>")
                     .append(esc(r.getReadStatus())).append("</span></td>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>").append(esc(r.getCallerName())).append("</td>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>").append(esc(r.getCallerNumber())).append("</td>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>").append(esc(r.getExtensionName())).append("</td>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>").append(esc(r.getDateTime())).append("</td>")
                     .append("<td style='padding:6px 8px;border-bottom:1px solid #e5e7eb;'>").append(esc(r.getCallbackBy())).append("</td>")
                     .append("</tr>");
        }

        String moreNote = records.size() > 20
                ? "<p style='color:#6b7280;font-size:13px;margin-top:8px;'>Showing 20 of "
                  + records.size() + " records. See the attached CSV for the full report.</p>"
                : "";

        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="margin:0;padding:0;background:#f9fafb;font-family:sans-serif;">
                  <div style="max-width:700px;margin:24px auto;background:#fff;border-radius:8px;
                              border:1px solid #e5e7eb;overflow:hidden;">

                    <!-- Header -->
                    <div style="background:#1d4ed8;padding:20px 24px;">
                      <h1 style="margin:0;color:#fff;font-size:18px;font-weight:600;">
                        📞 Voicemail Report — %s
                      </h1>
                      <p style="margin:4px 0 0;color:#bfdbfe;font-size:13px;">%s &nbsp;·&nbsp; Generated %s</p>
                    </div>

                    <!-- Summary tiles -->
                    <div style="display:flex;gap:16px;padding:20px 24px;background:#f0f9ff;border-bottom:1px solid #e5e7eb;">
                      <div style="flex:1;text-align:center;">
                        <div style="font-size:28px;font-weight:700;color:#1d4ed8;">%d</div>
                        <div style="font-size:12px;color:#6b7280;margin-top:2px;">Total Voicemails</div>
                      </div>
                      <div style="flex:1;text-align:center;">
                        <div style="font-size:28px;font-weight:700;color:#dc2626;">%d</div>
                        <div style="font-size:12px;color:#6b7280;margin-top:2px;">Unread</div>
                      </div>
                      <div style="flex:1;text-align:center;">
                        <div style="font-size:28px;font-weight:700;color:#16a34a;">%d</div>
                        <div style="font-size:12px;color:#6b7280;margin-top:2px;">Called Back</div>
                      </div>
                    </div>

                    <!-- Table preview -->
                    <div style="padding:20px 24px;">
                      <h2 style="font-size:14px;font-weight:600;color:#374151;margin:0 0 12px;">
                        Recent Voicemails
                      </h2>
                      <table style="width:100%%;border-collapse:collapse;font-size:13px;">
                        <thead>
                          <tr style="background:#f3f4f6;">
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Status</th>
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Caller</th>
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Number</th>
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Extension</th>
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Date & Time</th>
                            <th style="padding:8px;text-align:left;border-bottom:2px solid #d1d5db;">Called Back By</th>
                          </tr>
                        </thead>
                        <tbody>%s</tbody>
                      </table>
                      %s
                    </div>

                    <!-- Footer -->
                    <div style="padding:16px 24px;background:#f9fafb;border-top:1px solid #e5e7eb;
                                font-size:12px;color:#9ca3af;text-align:center;">
                      This report was generated by Voicemail CallBack Tracker.
                      The attached CSV contains the full dataset.
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                        periodLabel,
                        viewLabel,
                        today,
                        records.size(),
                        unread,
                        calledBack,
                        tableRows,
                        moreNote);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String csv(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
