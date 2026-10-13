package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Proxies RingCentral call recording audio to the browser via streaming.
 *
 * RC recording content requires a Bearer token — the browser cannot call it
 * directly. This endpoint fetches from RC and streams bytes to the browser.
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

    @Autowired
    private RingCentralConfig config;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @GetMapping("/{recordingId}/stream")
    public ResponseEntity<StreamingResponseBody> streamRecording(
            @PathVariable String recordingId,
            HttpSession session) {

        // Auth check
        String sessionId = session.getId();
        TokenStore.Entry entry = tokenStore.get(sessionId);
        if (entry == null || entry.token == null || entry.token.access_token == null) {
            return ResponseEntity.status(401).build();
        }
        String accessToken = entry.token.access_token;

        // Resolve content URL — prefer the exact contentUri from RC (correct host),
        // fall back to constructing from the server URL if not yet registered.
        String contentUri = recordingStore.getContentUri(recordingId);
        if (contentUri == null || contentUri.isBlank()) {
            // Fallback: try media.ringcentral.com directly
            // (RC always serves recording content from the media subdomain)
            String serverUrl = config.getServerUrl();
            String mediaBase = serverUrl.contains("devtest")
                    ? "https://media.devtest.ringcentral.com"
                    : "https://media.ringcentral.com";
            contentUri = mediaBase + "/restapi/v1.0/account/~/recording/" + recordingId + "/content";
            System.out.println("[RecordingController] contentUri not registered, using fallback: " + contentUri);
        } else {
            System.out.println("[RecordingController] streaming recording " + recordingId
                    + " from: " + contentUri);
        }

        final String finalContentUri = contentUri;

        StreamingResponseBody body = outputStream -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(finalContentUri))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "audio/mpeg, audio/wav, audio/*, */*")
                        .GET()
                        .build();

                HttpResponse<InputStream> rcResponse = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                int status = rcResponse.statusCode();
                System.out.println("[RecordingController] RC responded " + status
                        + " for recording " + recordingId);

                if (status != 200) {
                    // Read error body for logging
                    try (InputStream err = rcResponse.body()) {
                        byte[] errBytes = err.readAllBytes();
                        System.err.println("[RecordingController] RC error body: "
                                + new String(errBytes));
                    }
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
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
