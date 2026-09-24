package com.voicemail.tracker;

import com.ringcentral.definitions.GetExtensionInfoResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VoicemailController {

    @Autowired
    private VoicemailService voicemailService;

    // -----------------------------------------------------------------------
    // Voicemails — single extension
    // -----------------------------------------------------------------------

    @GetMapping("/voicemails")
    public ResponseEntity<?> getVoicemails(
            @RequestParam(value = "period",        defaultValue = "all") String period,
            @RequestParam(value = "extensionId",   defaultValue = "~")   String extensionId,
            @RequestParam(value = "withCallbacks", defaultValue = "false") boolean withCallbacks,
            @RequestParam(value = "dateFrom",      required = false)      String dateFrom,
            @RequestParam(value = "dateTo",        required = false)      String dateTo,
            HttpSession session) {
        try {
            String resolvedFrom = toIsoStart(dateFrom);
            String resolvedTo   = toIsoEnd(dateTo);

            List<VoicemailRecord> records = voicemailService.fetchVoicemails(
                    session.getId(), period, extensionId, resolvedFrom, resolvedTo);

            if (withCallbacks) {
                voicemailService.enrichWithCallbacks(session.getId(), records);
            }

            return ResponseEntity.ok(records);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Voicemails — all queues aggregated
    // -----------------------------------------------------------------------

    @GetMapping("/voicemails/queues")
    public ResponseEntity<?> getQueueVoicemails(
            @RequestParam(value = "period",        defaultValue = "all") String period,
            @RequestParam(value = "withCallbacks", defaultValue = "false") boolean withCallbacks,
            @RequestParam(value = "dateFrom",      required = false)      String dateFrom,
            @RequestParam(value = "dateTo",        required = false)      String dateTo,
            HttpSession session) {
        try {
            String resolvedFrom = toIsoStart(dateFrom);
            String resolvedTo   = toIsoEnd(dateTo);

            List<VoicemailRecord> records = voicemailService.fetchAllQueueVoicemails(
                    session.getId(), period, resolvedFrom, resolvedTo);

            if (withCallbacks) {
                voicemailService.enrichWithCallbacks(session.getId(), records);
            }

            return ResponseEntity.ok(records);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Voicemails — all users aggregated
    // -----------------------------------------------------------------------

    @GetMapping("/voicemails/users")
    public ResponseEntity<?> getAllUserVoicemails(
            @RequestParam(value = "period",        defaultValue = "all") String period,
            @RequestParam(value = "withCallbacks", defaultValue = "false") boolean withCallbacks,
            @RequestParam(value = "dateFrom",      required = false)      String dateFrom,
            @RequestParam(value = "dateTo",        required = false)      String dateTo,
            HttpSession session) {
        try {
            String resolvedFrom = toIsoStart(dateFrom);
            String resolvedTo   = toIsoEnd(dateTo);

            List<VoicemailRecord> records = voicemailService.fetchAllUserVoicemails(
                    session.getId(), period, resolvedFrom, resolvedTo);

            if (withCallbacks) {
                voicemailService.enrichWithCallbacks(session.getId(), records);
            }

            return ResponseEntity.ok(records);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Transcripts — lazy enrichment
    // -----------------------------------------------------------------------

    @PostMapping("/voicemails/enrich-transcripts")
    public ResponseEntity<?> enrichTranscripts(
            @RequestBody List<Map<String, String>> items,
            HttpSession session) {
        try {
            List<VoicemailRecord> stubs = new ArrayList<>();
            for (Map<String, String> item : items) {
                VoicemailRecord rec = new VoicemailRecord();
                rec.setId(item.get("id"));
                rec.setExtensionId(item.get("extensionId"));
                rec.setTranscriptAttachmentId(item.get("transcriptAttachmentId"));
                stubs.add(rec);
            }

            voicemailService.enrichWithTranscripts(session.getId(), stubs);

            List<Map<String, String>> result = new ArrayList<>();
            for (VoicemailRecord r : stubs) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                if (r.getTranscript()       != null) m.put("transcript",       r.getTranscript());
                if (r.getStatedCallerName() != null) m.put("statedCallerName", r.getStatedCallerName());
                result.add(m);
            }

            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Callbacks — lazy enrichment (mirrors enrich-transcripts pattern)
    // -----------------------------------------------------------------------

    /**
     * POST /api/voicemails/enrich-callbacks
     *
     * Accepts a JSON array of {@code {id, callerNumber, dateTime}} stubs —
     * the minimal data needed to run the callback-matching algorithm — and
     * returns {@code [{id, callbackBy, callbackTime}]} for the records where
     * a matching outbound call was found.
     *
     * <p>The frontend calls this endpoint <em>after</em> the initial
     * voicemail list has already rendered, so users see their voicemails
     * immediately while callback data fills in the background.
     */
    @PostMapping("/voicemails/enrich-callbacks")
    public ResponseEntity<?> enrichCallbacks(
            @RequestBody List<Map<String, String>> items,
            HttpSession session) {
        try {
            List<VoicemailRecord> stubs = new ArrayList<>();
            for (Map<String, String> item : items) {
                VoicemailRecord rec = new VoicemailRecord();
                rec.setId(item.get("id"));
                rec.setCallerNumber(item.get("callerNumber"));
                rec.setDateTime(item.get("dateTime"));
                stubs.add(rec);
            }

            voicemailService.enrichWithCallbacks(session.getId(), stubs);

            // Return only the enriched fields — frontend merges by id
            List<Map<String, String>> result = new ArrayList<>();
            for (VoicemailRecord r : stubs) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                if (r.getCallbackBy()          != null) m.put("callbackBy",          r.getCallbackBy());
                if (r.getCallbackTime()        != null) m.put("callbackTime",        r.getCallbackTime());
                if (r.getCallbackRecordingId() != null) m.put("callbackRecordingId", r.getCallbackRecordingId());
                result.add(m);
            }

            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Extensions / Queues list
    // -----------------------------------------------------------------------

    @GetMapping("/extensions")
    public ResponseEntity<?> getExtensions(HttpSession session) {
        try {
            List<ExtensionRecord> extensions = voicemailService.fetchExtensions(session.getId());
            return ResponseEntity.ok(extensions);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Current user
    // -----------------------------------------------------------------------

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpSession session) {
        try {
            GetExtensionInfoResponse me = voicemailService.getCurrentUser(session.getId());
            return ResponseEntity.ok(Map.of(
                    "name",            me.name            != null ? me.name            : "",
                    "extensionNumber", me.extensionNumber != null ? me.extensionNumber : "",
                    "type",            me.type            != null ? me.type            : ""
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String toIsoStart(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        } catch (Exception e) { return null; }
    }

    private String toIsoEnd(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return LocalDate.parse(date).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant().toString();
        } catch (Exception e) { return null; }
    }
}
