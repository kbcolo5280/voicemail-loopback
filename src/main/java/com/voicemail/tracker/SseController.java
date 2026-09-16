package com.voicemail.tracker;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes GET /api/sse — a persistent Server-Sent Events stream that the
 * browser connects to once after login.
 *
 * <p>When RingCentral delivers a webhook event, {@link WebhookController}
 * calls {@link SseBroadcaster#broadcast} and every connected browser
 * receives a {@code voicemail-update} event, triggering a fresh load.
 * The 60-second polling in the frontend remains active as a silent fallback
 * in case the SSE connection or webhook delivery fails.
 */
@RestController
@RequestMapping("/api")
public class SseController {

    @Autowired
    private SseBroadcaster broadcaster;

    @GetMapping("/sse")
    public SseEmitter stream(HttpServletResponse response) {
        // Prevent proxies / nginx from buffering the SSE stream
        response.setHeader("Cache-Control",     "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return broadcaster.register();
    }
}
