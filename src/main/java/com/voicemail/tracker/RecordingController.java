package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Proxies RingCentral call recording audio to the browser via streaming.
 *
 * RC recording content is hosted on media.ringcentral.com and requires a
 * Bearer token — the browser cannot call it directly. This endpoint:
 *  1. Looks up the exact contentUri (registered by VoicemailService during
 *     callback enrichment) from RecordingStore.
 *  2. Opens an authenticated HTTP connection to RC and pipes the audio
 *     InputStream directly to the response — no byte[] buffering, so
 *     large recordings don't consume heap.
 *
 * GET /api/recording/{recordingId}/stream
 */
@RestController
@RequestMapping("/api/recording")
public class RecordingController {

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private RecordingStore recordingStore;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)   // RC media URLs often redirect
            .build();

    @GetMapping("/{recordingId}/stream")
    public ResponseEntity<StreamingResponseBody> streamRecording(
            @PathVariable String recordingId,
            HttpSession session) {

        // --- Auth check ---
        String sessionId = session.getId();
        TokenStore.Entry entry = tokenStore.get(sessionId);
        if (entry == null || entry.token == null || entry.token.access_token == null) {
            return ResponseEntity.status(401).build();
        }

        // --- Look up the exact RC contentUri ---
        String contentUri = recordingStore.getContentUri(recordingId);
        if (contentUri == null || contentUri.isBlank()) {
            // ContentUri not yet registered (page loaded before enrichment ran
            // or container restarted). Tell browser to retry after a refresh.
            System.err.println("[RecordingController] contentUri not registered for id="
                    + recordingId + " — user should refresh the page");
            return ResponseEntity.status(404)
                    .header("X-Recording-Error", "not-registered")
                    .build();
        }

        String accessToken = entry.token.access_token;

        // --- Stream the audio from RC ---
        StreamingResponseBody body = outputStream -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(contentUri))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "audio/mpeg, audio/wav, audio/*, */*")
                        .GET()
                        .build();

                HttpResponse<InputStream> rcResponse = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (rcResponse.statusCode() != 200) {
                    System.err.println("[RecordingController] RC returned "
                            + rcResponse.statusCode() + " for recording " + recordingId);
                    return;
                }

                try (InputStream in = rcResponse.body()) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        outputStream.write(buf, 0, read);
                    }
                    outputStream.flush();
                }
            } catch (Exception e) {
                System.err.println("[RecordingController] Stream error for recording "
                        + recordingId + ": " + e.getMessage());
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"callback-recording-" + recordingId + ".mp3\"")
                .header("X-Accel-Buffering", "no")   // disable nginx buffering on Railway
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
