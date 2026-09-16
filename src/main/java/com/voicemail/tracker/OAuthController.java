package com.voicemail.tracker;

import com.ringcentral.RestClient;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles the RingCentral OAuth 2.0 Authorization Code flow.
 *
 *  Step 1 – User hits /oauth/login  → redirected to RingCentral consent page
 *  Step 2 – RingCentral redirects back to /oauth/callback?code=...&state=...
 *  Step 3 – We verify state, exchange the code for tokens, and store them
 *  Step 4 – User is redirected to the dashboard at /
 *
 * Multiple users can be logged in simultaneously: each browser session has its
 * own session ID, and that ID keys their token in {@link TokenStore} and their
 * extensions cache in {@link VoicemailService}.
 */
@Controller
public class OAuthController {

    @Autowired
    private RingCentralConfig config;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private VoicemailService voicemailService;

    @Autowired
    private WebhookService webhookService;

    /**
     * Kick off the OAuth flow.  The session ID is used as the CSRF 'state'
     * parameter so we can verify the callback belongs to this browser session.
     */
    @GetMapping("/oauth/login")
    public String initiateLogin(HttpSession session) {
        String state = session.getId();

        String authUrl = config.getServerUrl()
                + "/restapi/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(config.getClientId())
                + "&redirect_uri=" + encode(config.getRedirectUri())
                + "&state=" + encode(state);

        return "redirect:" + authUrl;
    }

    /**
     * RingCentral posts back here with ?code=... after the user approves.
     *
     * <p>We first verify the {@code state} parameter matches this browser's
     * session ID — a mismatch signals either a CSRF attempt or a session that
     * expired between login and callback, both of which require a fresh login.
     *
     * <p>On success we exchange the authorization code for an access/refresh
     * token pair and store it in the {@link TokenStore} keyed by session ID.
     */
    @GetMapping("/oauth/callback")
    public String handleCallback(
            @RequestParam("code") String code,
            @RequestParam(value = "state", required = false) String state,
            HttpSession session) {

        // ── CSRF / session-integrity check ────────────────────────────────
        if (state == null || !state.equals(session.getId())) {
            return "redirect:/?error=" + encode(
                    "Login state mismatch — please try logging in again.");
        }

        try {
            RestClient rc = new RestClient(
                    config.getClientId(),
                    config.getClientSecret(),
                    config.getServerUrl());

            rc.authorize(code, config.getRedirectUri());
            tokenStore.save(session.getId(), rc.token);

            // Register the RC webhook subscription on the first login (no-op after that)
            webhookService.ensureRegistered(session.getId());

            return "redirect:/";
        } catch (Exception e) {
            String msg = encode(e.getMessage() != null ? e.getMessage() : "Unknown error");
            return "redirect:/?error=" + msg;
        }
    }

    /**
     * Clears the user's token and session-scoped caches, then invalidates
     * the HTTP session so a fresh login is required.
     */
    @GetMapping("/oauth/logout")
    public String logout(HttpSession session) {
        String sessionId = session.getId();
        tokenStore.remove(sessionId);
        voicemailService.clearSessionCache(sessionId);  // evict extensions cache
        session.invalidate();
        return "redirect:/";
    }

    /**
     * Simple JSON boolean used by the frontend to check auth state.
     */
    @GetMapping("/oauth/status")
    @ResponseBody
    public boolean isAuthenticated(HttpSession session) {
        return tokenStore.hasToken(session.getId());
    }

    // -------------------------------------------------------------------------

    private static String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
