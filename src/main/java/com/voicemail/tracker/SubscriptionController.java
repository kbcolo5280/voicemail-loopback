package com.voicemail.tracker;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST CRUD for email report subscriptions.
 *
 *  GET    /api/subscriptions           → list all
 *  POST   /api/subscriptions           → create new
 *  PUT    /api/subscriptions/{id}      → update existing
 *  DELETE /api/subscriptions/{id}      → delete
 *  POST   /api/subscriptions/{id}/send → run immediately (manual trigger)
 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    @Autowired
    private SubscriptionStore store;

    @Autowired
    private SubscriptionScheduler scheduler;

    // ── List ────────────────────────────────────────────────────────────────

    @GetMapping
    public List<SubscriptionRecord> list() {
        return store.getAll();
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @PostMapping
    public SubscriptionRecord create(@RequestBody SubscriptionRecord rec) {
        rec.setId(UUID.randomUUID().toString());
        rec.setCreatedAt(Instant.now().toString());
        store.save(rec);
        return rec;
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody SubscriptionRecord incoming) {
        return store.findById(id)
                .map(existing -> {
                    incoming.setId(id);
                    incoming.setCreatedAt(existing.getCreatedAt()); // preserve original creation time
                    store.save(incoming);
                    return ResponseEntity.ok(incoming);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return store.delete(id)
                ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.notFound().build();
    }

    // ── Manual send ─────────────────────────────────────────────────────────

    /**
     * Immediately runs a subscription's report and sends it, regardless of
     * its schedule.  Useful for testing a newly created subscription.
     */
    @PostMapping("/{id}/send")
    public ResponseEntity<?> sendNow(@PathVariable String id, HttpSession session) {
        return store.findById(id)
                .map(sub -> {
                    try {
                        scheduler.runSubscription(sub, session.getId());
                        return ResponseEntity.ok(Map.of("sent", true,
                                "recipients", sub.getRecipients().size()));
                    } catch (Exception e) {
                        return ResponseEntity.status(500)
                                .body(Map.of("sent", false,
                                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
