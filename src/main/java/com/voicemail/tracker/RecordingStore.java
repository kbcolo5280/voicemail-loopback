package com.voicemail.tracker;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the RC contentUri for each callback recording ID.
 *
 * RC recording content lives at media.ringcentral.com, not platform.ringcentral.com.
 * The exact URI comes from CallLogRecordingInfo.contentUri in the call log response.
 * We store it here (keyed by recording ID) so RecordingController can proxy requests
 * without exposing the RC media URL to the browser.
 *
 * The map is in-memory; it repopulates automatically when the dashboard enriches
 * callbacks after a page reload, so restarts are self-healing.
 */
@Component
public class RecordingStore {

    private final ConcurrentHashMap<String, String> contentUris = new ConcurrentHashMap<>();

    /** Called during callback enrichment when a recorded call is found. */
    public void register(String recordingId, String contentUri) {
        if (recordingId != null && contentUri != null
                && !recordingId.isBlank() && !contentUri.isBlank()) {
            contentUris.put(recordingId, contentUri);
        }
    }

    /** Returns the RC contentUri for this recording ID, or null if not yet registered. */
    public String getContentUri(String recordingId) {
        return contentUris.get(recordingId);
    }
}
