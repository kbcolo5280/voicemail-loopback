package com.voicemail.tracker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory store for {@link SubscriptionRecord} objects.
 * Keyed by subscription ID (UUID string).
 *
 * NOTE: Data is lost on server restart.  For production, persist to a database.
 */
@Component
public class SubscriptionStore {

    private final ConcurrentHashMap<String, SubscriptionRecord> store = new ConcurrentHashMap<>();

    /** Returns all subscriptions sorted by creation time (oldest first). */
    public List<SubscriptionRecord> getAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(
                        r -> (r.getCreatedAt() != null ? r.getCreatedAt() : "")))
                .collect(Collectors.toList());
    }

    /** Saves (creates or replaces) a subscription. */
    public void save(SubscriptionRecord rec) {
        if (rec != null && rec.getId() != null) {
            store.put(rec.getId(), rec);
        }
    }

    /** Finds a subscription by ID. */
    public Optional<SubscriptionRecord> findById(String id) {
        return Optional.ofNullable(id != null ? store.get(id) : null);
    }

    /**
     * Deletes a subscription by ID.
     * @return {@code true} if it existed and was removed
     */
    public boolean delete(String id) {
        return id != null && store.remove(id) != null;
    }

    /** Returns the total number of subscriptions. */
    public int size() {
        return store.size();
    }
}
