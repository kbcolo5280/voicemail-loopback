package com.voicemail.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ringcentral.definitions.TokenInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store mapping HTTP session IDs to RingCentral OAuth tokens.
 * Persists to /data/tokens.json so the scheduler can fire reports even after
 * a server restart without requiring a user to log in again.
 */
@Component
public class TokenStore {

    private static final Logger log      = LoggerFactory.getLogger(TokenStore.class);
    private static final String DATA_DIR  = "/data";
    private static final String DATA_FILE = "/data/tokens.json";

    /**
     * Fixed session key used by the background scheduler so it can fire
     * email reports even after all browser sessions have expired or logged out.
     * Saved whenever any user successfully completes OAuth.
     */
    public static final String SCHEDULER_SESSION = "_scheduler";

    private final ObjectMapper mapper = new ObjectMapper();

    public static class Entry {
        public final TokenInfo token;
        public final long expiresAtMs;

        public Entry(TokenInfo token, long expiresAtMs) {
            this.token       = token;
            this.expiresAtMs = expiresAtMs;
        }

        public boolean isExpiringSoon() {
            return System.currentTimeMillis() >= expiresAtMs - 120_000L;
        }
    }

    public static class PersistedToken {
        public String sessionId;
        public String accessToken;
        public String refreshToken;
        public String tokenType;
        public Long   expiresIn;
        public Long   refreshTokenExpiresIn;
        public String scope;
        public String ownerId;
        public String endpointId;
        public long   expiresAtMs;
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) {
            log.info("[TokenStore] No existing token file — starting fresh");
            return;
        }
        try {
            List<PersistedToken> list = mapper.readValue(f,
                    new TypeReference<List<PersistedToken>>() {});
            int loaded = 0;
            for (PersistedToken pt : list) {
                if (pt.sessionId == null || pt.refreshToken == null) continue;
                store.put(pt.sessionId, new Entry(toTokenInfo(pt), pt.expiresAtMs));
                loaded++;
            }
            log.info("[TokenStore] Loaded {} token(s) from disk", loaded);
        } catch (Exception e) {
            log.error("[TokenStore] Failed to load from disk: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            List<PersistedToken> list = new ArrayList<>();
            for (Map.Entry<String, Entry> e : store.entrySet()) {
                list.add(toPersistedToken(e.getKey(), e.getValue()));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(DATA_FILE), list);
        } catch (Exception e) {
            log.error("[TokenStore] Failed to persist to disk: {}", e.getMessage());
        }
    }

    public void save(String sessionId, TokenInfo token) {
        long ttlMs = (token.expires_in != null)
                ? token.expires_in * 1_000L
                : 3_600_000L;
        store.put(sessionId, new Entry(token, System.currentTimeMillis() + ttlMs));
        persist();
    }

    public Entry get(String sessionId) {
        return store.get(sessionId);
    }

    public boolean hasToken(String sessionId) {
        return store.containsKey(sessionId);
    }

    public void remove(String sessionId) {
        store.remove(sessionId);
        persist();
    }

    /**
     * Returns a session ID suitable for the background scheduler.
     * <ol>
     *   <li>Prefers the dedicated {@value #SCHEDULER_SESSION} background token
     *       (active or refreshable) — it survives user logout and server restarts.</li>
     *   <li>Falls back to any other active user-session token.</li>
     *   <li>As a last resort returns any expired-but-refreshable entry so
     *       {@link VoicemailService#getClient} can attempt a token refresh.</li>
     * </ol>
     * Returns {@code null} only if the store is empty or every token is both
     * expired and carries no refresh token.
     */
    public String getAnyActiveSessionId() {
        long now = System.currentTimeMillis();

        // 1. Prefer the dedicated background scheduler token
        Entry schedulerEntry = store.get(SCHEDULER_SESSION);
        if (schedulerEntry != null) {
            if (schedulerEntry.expiresAtMs > now) {
                return SCHEDULER_SESSION;   // active
            }
            if (schedulerEntry.token != null && schedulerEntry.token.refresh_token != null) {
                return SCHEDULER_SESSION;   // expired but refreshable
            }
        }

        // 2. Fall back to any active user session
        String refreshable = null;
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (SCHEDULER_SESSION.equals(e.getKey())) continue;
            if (e.getValue().expiresAtMs > now) {
                return e.getKey();          // active user session
            }
            if (e.getValue().token != null
                    && e.getValue().token.refresh_token != null
                    && refreshable == null) {
                refreshable = e.getKey();   // expired but refreshable — keep as fallback
            }
        }
        return refreshable;
    }

    private static PersistedToken toPersistedToken(String sessionId, Entry e) {
        PersistedToken pt = new PersistedToken();
        pt.sessionId             = sessionId;
        pt.expiresAtMs           = e.expiresAtMs;
        if (e.token != null) {
            pt.accessToken           = e.token.access_token;
            pt.refreshToken          = e.token.refresh_token;
            pt.tokenType             = e.token.token_type;
            pt.expiresIn             = e.token.expires_in;
            pt.refreshTokenExpiresIn = e.token.refresh_token_expires_in;
            pt.scope                 = e.token.scope;
            pt.ownerId               = e.token.owner_id;
            pt.endpointId            = e.token.endpoint_id;
        }
        return pt;
    }

    private static TokenInfo toTokenInfo(PersistedToken pt) {
        TokenInfo ti = new TokenInfo();
        ti.access_token             = pt.accessToken;
        ti.refresh_token            = pt.refreshToken;
        ti.token_type               = pt.tokenType;
        ti.expires_in               = pt.expiresIn;
        ti.refresh_token_expires_in = pt.refreshTokenExpiresIn;
        ti.scope                    = pt.scope;
        ti.owner_id                 = pt.ownerId;
        ti.endpoint_id              = pt.endpointId;
        return ti;
    }
}
