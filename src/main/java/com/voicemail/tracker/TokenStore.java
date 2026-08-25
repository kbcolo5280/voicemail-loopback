package com.voicemail.tracker;

import com.ringcentral.definitions.TokenInfo;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store mapping HTTP session IDs to RingCentral
 * OAuth tokens together with each token's absolute expiry timestamp.
 *
 * Each browser session gets its own entry so multiple users can be
 * logged in simultaneously without interfering with one another.
 *
 * NOTE: Entries are lost on server restart.  For production, persist
 * to a database and encrypt the stored token values.
 */
@Component
public class TokenStore {

    // -----------------------------------------------------------------------
    // Inner type
    // -----------------------------------------------------------------------

    /**
     * Wraps a {@link TokenInfo} with the epoch-millisecond time at which
     * the access token expires.
     */
    public static class Entry {
        public final TokenInfo token;
        /** Epoch-ms at which the access token expires. */
        public final long expiresAtMs;

        public Entry(TokenInfo token, long expiresAtMs) {
            this.token       = token;
            this.expiresAtMs = expiresAtMs;
        }

        /**
         * Returns {@code true} when the access token will expire within the
         * next 2 minutes (or has already expired) and should be refreshed
         * before making API calls.
         */
        public boolean isExpiringSoon() {
            return System.currentTimeMillis() >= expiresAtMs - 120_000L;
        }
    }

    // -----------------------------------------------------------------------
    // Store
    // -----------------------------------------------------------------------

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * Saves (or replaces) the token for a session, computing the absolute
     * expiry from {@code token.expires_in}.  Defaults to 1 hour if that
     * field is absent.
     */
    public void save(String sessionId, TokenInfo token) {
        long ttlMs = (token.expires_in != null)
                ? token.expires_in * 1_000L
                : 3_600_000L;
        store.put(sessionId, new Entry(token, System.currentTimeMillis() + ttlMs));
    }

    /** Returns the stored {@link Entry}, or {@code null} if none exists. */
    public Entry get(String sessionId) {
        return store.get(sessionId);
    }

    /** Returns {@code true} if a token entry exists for this session. */
    public boolean hasToken(String sessionId) {
        return store.containsKey(sessionId);
    }

    /** Removes the token entry for this session (logout / expiry). */
    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
