package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives RingCentral webhook push notifications at POST /api/webhook/voicemail.
 *
 * <p>RC validates a new webhook endpoint by sending a POST whose
 * {@code Validation-Token} header must be echoed back in the response header
 * of the same name (and optionally in the body).  After validation passes,
 * RC starts delivering real event payloads to this URL.
 *
 * <p>On a real voicemail event the body contains JSON with a {@code changes}
 * array that includes an entry with {@code "type":"VoiceMail"}.  We do a
 * simple string-contains check (no JSON parsing dependency needed) and fan
 * the signal out to every connected SSE client via {@link SseBroadcaster}.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    @Autowired
    private SseBroadcaster broadcaster;

    @PostMapping("/voicemail")
    public ResponseEntity<String> receive(
            @RequestHeader(value = "Validation-Token", required = false) String validationToken,
            @RequestBody(required = false) String body) {

        // ── RC endpoint validation handshake ─────────────────────────────
        // RC sends this header when the subscription is first created.
        // We must return it in the response header (and body) with HTTP 200.
        if (validationToken != null && !validationToken.isBlank()) {
            System.out.println("[Webhook] Validation handshake — responding with token");
            return ResponseEntity.ok()
                    .header("Validation-Token", validationToken)
                    .body(validationToken);
        }

        // ── Real event ────────────────────────────────────────────────────
        if (body != null && body.contains("VoiceMail")) {
            System.out.println("[Webhook] Voicemail event — broadcasting to "
                    + broadcaster.activeCount() + " SSE client(s)");
            broadcaster.broadcast("voicemail-update", "{\"type\":\"voicemail\"}");
        } else {
            System.out.println("[Webhook] Non-voicemail event received, ignoring");
        }

        return ResponseEntity.ok("OK");
    }
}
