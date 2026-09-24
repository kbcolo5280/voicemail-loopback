package com.voicemail.tracker;

/**
 * DTO returned to the frontend for each voicemail message.
 */
public class VoicemailRecord {

    private String id;

    /** Inbound caller's phone number */
    private String callerNumber;

    /** Inbound caller's name (if available via caller ID) */
    private String callerName;

    /** "Read" or "Unread" */
    private String readStatus;

    /** ISO-8601 timestamp when the voicemail was left */
    private String dateTime;

    /** Extension ID (user or queue) that the voicemail belongs to */
    private String extensionId;

    /** Display name of the extension / queue */
    private String extensionName;

    /** Extension type: "User", "Department" (call queue), etc. */
    private String extensionType;

    /** Name of the person who called back (from call log, if found) */
    private String callbackBy;

    /** ISO-8601 timestamp of the callback call (from call log, if found) */
    private String callbackTime;

    /** Voicemail duration in seconds (from attachment metadata) */
    private int duration;

    /**
     * Caller name extracted from the voicemail transcript body —
     * e.g. "Ken Smith" from "This is Ken Smith, please call me back."
     * Used for cross-number callback matching when the same person
     * calls from different phone numbers.
     */
    private String statedCallerName;

    /**
     * Full voicemail-to-text transcript (AudioTranscription attachment content).
     * Null until lazily enriched via POST /api/voicemails/enrich-transcripts.
     */
    private String transcript;

    /**
     * RingCentral attachment ID for the AudioTranscription attachment.
     * Populated during initial message fetch; used by the enrich endpoint
     * to fetch transcript content without re-fetching the full message.
     */
    private String transcriptAttachmentId;

    /**
     * RingCentral recording ID for the outbound callback call recording.
     * Populated during callback enrichment if the call was recorded.
     * Used by /api/recording/{id}/stream to proxy the audio with auth.
     */
    private String callbackRecordingId;

    // ----- Getters & Setters -----

    public String getId()                               { return id; }
    public void   setId(String id)                      { this.id = id; }

    public String getCallerNumber()                     { return callerNumber; }
    public void   setCallerNumber(String v)             { this.callerNumber = v; }

    public String getCallerName()                       { return callerName; }
    public void   setCallerName(String v)               { this.callerName = v; }

    public String getReadStatus()                       { return readStatus; }
    public void   setReadStatus(String v)               { this.readStatus = v; }

    public String getDateTime()                         { return dateTime; }
    public void   setDateTime(String v)                 { this.dateTime = v; }

    public String getExtensionId()                      { return extensionId; }
    public void   setExtensionId(String v)              { this.extensionId = v; }

    public String getExtensionName()                    { return extensionName; }
    public void   setExtensionName(String v)            { this.extensionName = v; }

    public String getExtensionType()                    { return extensionType; }
    public void   setExtensionType(String v)            { this.extensionType = v; }

    public String getCallbackBy()                       { return callbackBy; }
    public void   setCallbackBy(String v)               { this.callbackBy = v; }

    public String getCallbackTime()                     { return callbackTime; }
    public void   setCallbackTime(String v)             { this.callbackTime = v; }

    public int    getDuration()                         { return duration; }
    public void   setDuration(int v)                    { this.duration = v; }

    public String getStatedCallerName()                 { return statedCallerName; }
    public void   setStatedCallerName(String v)         { this.statedCallerName = v; }

    public String getTranscript()                       { return transcript; }
    public void   setTranscript(String v)               { this.transcript = v; }

    public String getTranscriptAttachmentId()           { return transcriptAttachmentId; }
    public void   setTranscriptAttachmentId(String v)   { this.transcriptAttachmentId = v; }

    public String getCallbackRecordingId()               { return callbackRecordingId; }
    public void   setCallbackRecordingId(String v)       { this.callbackRecordingId = v; }
}
