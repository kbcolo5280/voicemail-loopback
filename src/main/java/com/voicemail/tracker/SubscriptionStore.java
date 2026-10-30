package com.voicemail.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe store for {@link SubscriptionRecord} objects.
 * Persists to /data/subscriptions.json so data survives restarts.
 */
@Component
public class SubscriptionStore {

    private static final Logger log      = LoggerFactory.getLogger(SubscriptionStore.class);
    private static final String DATA_DIR  = "/data";
    private static final String DATA_FILE = "/data/subscriptions.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, SubscriptionRecord> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) {
            log.info("[SubscriptionStore] No existing data file — starting fresh");
            return;
        }
        try {
            List<SubscriptionRecord> list = mapper.readValue(f,
                    new TypeReference<List<SubscriptionRecord>>() {});
            list.forEach(r -> { if (r.getId() != null) store.put(r.getId(), r); });
            log.info("[SubscriptionStore] Loaded {} subscription(s) from disk", store.size());
        } catch (Exception e) {
            log.error("[SubscriptionStore] Failed to load from disk: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            mapper.writerWithDefaultPrettyPrinter()
                  .writeValue(new File(DATA_FILE), new ArrayList<>(store.values()));
        } catch (Exception e) {
            log.error("[SubscriptionStore] Failed to persist to disk: {}", e.getMessage());
        }
    }

    public List<SubscriptionRecord> getAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(
                        r -> (r.getCreatedAt() != null ? r.getCreatedAt() : "")))
                .collect(Collectors.toList());
    }

    public void save(SubscriptionRecord rec) {
        if (rec != null && rec.getId() != null) {
            store.put(rec.getId(), rec);
            persist();
        }
    }

    public Optional<SubscriptionRecord> findById(String id) {
        return Optional.ofNullable(id != null ? store.get(id) : null);
    }

    public boolean delete(String id) {
        boolean removed = id != null && store.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    public int size() {
        return store.size();
    }
}
