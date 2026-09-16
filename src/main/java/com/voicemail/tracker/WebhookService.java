package com.voicemail.tracker;

import com.ringcentral.RestClient;
import com.ringcentral.definitions.CreateSubscriptionRequest;
import com.ringcentral.definitions.NotificationDeliveryModeRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manages the RingCentral webhook subscription lifecycle.
 *
 * <ul>
 *   <li>Registers the webhook on the first successful user login via
 *       {@link #ensureRegistered(String)}.</li>
 *   <li>Checks daily whether the subscription is within 5 days of expiry
 *       and renews it automatically.</li>
 * </ul>
 *
 * <p>The subscription is account-scoped, so any valid user token works for
 * registration.  We keep a reference to the first session that registered
 * and reuse it for renewals (a new session is tried if the original expires).
 *
 * <p>Set the {@code APP_BASE_URL} environment variable in Railway to your
 * service's public URL.  The default is the production Railway domain.
 */
@Service
public class WebhookService {

    @Autowired
    private RingCentralConfig config;

    @Autowired
    private TokenStore tokenStore;

    /** Public base URL of this service — RC must be able to reach it. */
    @Value("${APP_BASE_URL:https://voicemail-loopback-production.up.railway.app}")
    private String baseUrl;

    private volatile String  subscriptionId      = null;
    private volatile Instant expiresAt           = null;
    private volatile String  registeredSessionId = null;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Registers the webhook if not already registered.  Safe to call on every
     * login — the guard makes it a no-op once a subscription exists.
     */
    public synchronized void ensureRegistered(String sessionId) {
        if (subscriptionId != null) return;
        try {
            register(sessionId);
        } catch (Exception e) {
            System.err.println("[WebhookService] Registration failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Scheduled renewal — runs once per day
    // -----------------------------------------------------------------------

    /**
     * Fires daily.  If the subscription expires within the next 5 days,
     * a new one is registered (RC does not support in-place renewal via the
     * Java SDK's simple PUT — creating a fresh subscription is equivalent).
     */
    @Scheduled(fixedDelay = 24L * 60 * 60 * 1000)
    public synchronized void checkAndRenew() {
        if (subscriptionId == null || expiresAt == null || registeredSessionId == null) return;
        if (Instant.now().isBefore(expiresAt.minus(5, ChronoUnit.DAYS))) return;

        System.out.println("[WebhookService] Subscription nearing expiry — renewing");
        try {
            register(registeredSessionId);
        } catch (Exception e) {
            System.err.println("[WebhookService] Renewal failed: " + e.getMessage());
            // Reset so the next login attempt triggers a fresh registration
            subscriptionId = null;
            expiresAt      = null;
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void register(String sessionId) throws Exception {
        TokenStore.Entry entry = tokenStore.get(sessionId);
        if (entry == null) {
            throw new IllegalStateException("No token available for session " + sessionId);
        }

        RestClient rc = new RestClient(
                config.getClientId(),
                config.getClientSecret(),
                config.getServerUrl());
        rc.token = entry.token;

        // Event filter: any message-store change on any extension in the account
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.eventFilters = new String[]{
            "/restapi/v1.0/account/~/extension/~/message-store"
        };

        NotificationDeliveryModeRequest delivery = new NotificationDeliveryModeRequest();
        delivery.transportType = "WebHook";
        delivery.address       = baseUrl + "/api/webhook/voicemail";
        req.deliveryMode       = delivery;

        // RC enforces a maximum of 30 days regardless of what we request
        req.expiresIn = 2_592_000L;

        var response = rc.restapi().subscription().post(req);

        subscriptionId      = response.id;
        registeredSessionId = sessionId;

        // Parse the expiry time RC returns; fall back to 30 days from now
        if (response.expirationTime != null) {
            try {
                expiresAt = Instant.parse(response.expirationTime);
            } catch (Exception ignored) {
                expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
            }
        } else {
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        }

        System.out.println("[WebhookService] Webhook registered — id=" + subscriptionId
                + ", expires=" + expiresAt);
    }
}
