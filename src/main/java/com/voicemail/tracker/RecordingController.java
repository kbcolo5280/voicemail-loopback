package com.voicemail.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Proxies RingCentral call recording audio to the browser.
 *
 * RC recording content URLs require a Bearer token — the browser cannot call
 * them directly without exposing the access token in JavaScript.  This
 * endpoint fetches the audio server-side using the logged-in user's token
 * and streams the bytes back with appropriate audio/* content headers.
 *
 * GET /api/recording/{recordingId}/stream
 */
@RestController
@RequestMapping("/api/recording")
public class RecordingController {

    @Autowired
    private RingCentralConfig config;

    @Autowired
    private TokenStore tokenStore;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @GetMapping("/{recordingId}/stream")
    public ResponseEntity<byte[]> streamRecording(
            @PathVariable String recordingId,
            HttpSession session) {

        String sessionId = session.getId();
        TokenStore.Entry entry = tokenStore.get(sessionId);
        if (entry == null || entry.token == null || entry.token.access_token == null) {
            return ResponseEntity.status(401).build();
        }

        String accessToken = entry.token.access_token;
        // RC recording content URI — uses account-level endpoint so admin token works
        String url = config.getServerUrl()
                + "/restapi/v1.0/account/~/recording/" + recordingId + "/content";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "audio/mpeg, audio/wav, audio/*")
                    .GET()
                    .build();

            HttpResponse<byte[]> rcResponse = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());

            if (rcResponse.statusCode() == 401 || rcResponse.statusCode() == 403) {
                return ResponseEntity.status(rcResponse.statusCode()).build();
            }
            if (rcResponse.statusCode() != 200) {
                System.err.println("[RecordingController] RC returned "
                        + rcResponse.statusCode() + " for recording " + recordingId);
                return ResponseEntity.status(rcResponse.statusCode()).build();
            }

            byte[] audio = rcResponse.body();
            if (audio == null || audio.length == 0) {
                return ResponseEntity.notFound().build();
            }

            // Detect content type from RC response; default to audio/mpeg
            String contentType = rcResponse.headers()
                    .firstValue("content-type")
                    .orElse("audio/mpeg");
            // Strip quality parameters if present (e.g. "audio/mpeg;codecs=...")
            if (contentType.contains(";")) {
                contentType = contentType.split(";")[0].trim();
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(audio.length))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"callback-recording-" + recordingId + ".mp3\"")
                    .header("Accept-Ranges", "bytes")
                    .body(audio);

        } catch (Exception e) {
            System.err.println("[RecordingController] Error fetching recording "
                    + recordingId + ": " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
