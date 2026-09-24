package com.voicemail.tracker;

import com.ringcentral.RestClient;
import com.ringcentral.definitions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VoicemailService {

    @Autowired
    private RingCentralConfig config;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private RecordingStore recordingStore;

    // -----------------------------------------------------------------------
    // Thread pool
    // -----------------------------------------------------------------------

    /**
     * Shared thread pool for parallel RC API calls.
     * 20 threads gives enough parallelism for accounts with 30+ extensions
     * without busting RC's per-account rate limit.
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(20);

    // -----------------------------------------------------------------------
    // Session state: token refresh locks + extensions cache
    // -----------------------------------------------------------------------

    /** One lock object per session prevents concurrent token-refresh races. */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Per-session cache for the extensions list, which rarely changes and is
     * fetched on every page load (both from /api/extensions and internally
     * inside the aggregate voicemail endpoints).  A 5-minute TTL is safe for
     * almost all use-cases while still picking up newly added extensions.
     */
    private static final long EXTENSIONS_CACHE_TTL_MS = 5 * 60 * 1_000L;

    private final ConcurrentHashMap<String, List<ExtensionRecord>> extensionsCache    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>                  extensionsCacheAge = new ConcurrentHashMap<>();

    /**
     * Evicts all session-specific state (token-refresh lock, extensions cache)
     * for the given session.  Called on logout so memory is reclaimed promptly.
     */
    public void clearSessionCache(String sessionId) {
        sessionLocks.remove(sessionId);
        extensionsCache.remove(sessionId);
        extensionsCacheAge.remove(sessionId);
    }

    // -----------------------------------------------------------------------
    // Stated-name pattern
    // -----------------------------------------------------------------------

    private static final Pattern STATED_NAME_PATTERN = Pattern.compile(
            "(?:this is|my name is|it'?s|i'?m)\\s+([A-Z][a-zA-Z'-]+(?:\\s+(?!(?:And|Or|But|To|In|At|For|With|From|Just|The|A|An|Of|On|Up|So|About|Calling|Calling|Reaching|Following|Trying|Getting)\\b)[A-Z][a-zA-Z'-]+){0,2})",
            Pattern.CASE_INSENSITIVE);

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link RestClient} for the given session, proactively
     * refreshing the access token if it is within 2 minutes of expiry.
     * A per-session lock prevents multiple threads from racing on a refresh.
     *
     * @throws IllegalStateException if there is no token (not logged in) or the
     *   refresh token has also expired and the user must log in again.
     */
    private RestClient getClient(String sessionId) throws Exception {
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());

        synchronized (lock) {
            TokenStore.Entry entry = tokenStore.get(sessionId);
            if (entry == null) {
                throw new IllegalStateException(
                        "Not authenticated. Please log in via /oauth/login.");
            }

            RestClient rc = new RestClient(
                    config.getClientId(),
                    config.getClientSecret(),
                    config.getServerUrl());
            rc.token = entry.token;

            if (entry.isExpiringSoon()) {
                try {
                    rc.refresh();
                    tokenStore.save(sessionId, rc.token);
                } catch (Exception e) {
                    tokenStore.remove(sessionId);
                    throw new IllegalStateException(
                            "Session expired. Please log in again via /oauth/login.");
                }
            }

            return rc;
        }
    }

    /**
     * Converts a period shorthand into an ISO-8601 dateFrom string.
     * Supported: today, 7d, 30d, 60d, 90d, 180d, ytd, all
     */
    private String periodToDateFrom(String period) {
        if (period == null || period.isBlank() || "all".equalsIgnoreCase(period)) {
            return null;
        }
        switch (period.toLowerCase()) {
            case "today":
                return LocalDate.now(ZoneOffset.UTC)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            case "7d":
                return Instant.now().minus(7, ChronoUnit.DAYS).toString();
            case "30d":
                return Instant.now().minus(30, ChronoUnit.DAYS).toString();
            case "60d":
                return Instant.now().minus(60, ChronoUnit.DAYS).toString();
            case "90d":
                return Instant.now().minus(90, ChronoUnit.DAYS).toString();
            case "180d":
                return Instant.now().minus(180, ChronoUnit.DAYS).toString();
            case "ytd":
                return LocalDate.of(LocalDate.now(ZoneOffset.UTC).getYear(), 1, 1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            default:
                return null;
        }
    }

    private boolean nameIsPhoneNumber(String name, String phoneNumber) {
        if (name == null || name.isBlank()) return true;
        if (phoneNumber == null || phoneNumber.isBlank()) return false;
        String nameDigits  = name.replaceAll("[^0-9]", "");
        String phoneDigits = phoneNumber.replaceAll("[^0-9]", "");
        if (phoneDigits.isEmpty() || nameDigits.isEmpty()) return false;
        return nameDigits.equals(phoneDigits)
                || nameDigits.endsWith(phoneDigits)
                || phoneDigits.endsWith(nameDigits);
    }

    private String normalizeNumber(String phoneNumber) {
        if (phoneNumber == null) return null;
        return phoneNumber.startsWith("+") ? phoneNumber.substring(1) : phoneNumber;
    }

    private String extractStatedCallerName(String transcript) {
        if (transcript == null || transcript.isBlank()) return null;
        Matcher m = STATED_NAME_PATTERN.matcher(transcript);
        return m.find() ? m.group(1).trim() : null;
    }

    // -----------------------------------------------------------------------
    // Voicemail fetching — single extension (all pages)
    // -----------------------------------------------------------------------

    /**
     * Fetches ALL pages of voicemails for one extension.
     * Uses a 250-record page size (RC max is 500) to minimise the number of
     * sequential page-fetch round-trips for busy extensions.
     */
    public List<VoicemailRecord> fetchVoicemails(
            String sessionId,
            String period,
            String extensionId,
            String overrideDateFrom,
            String overrideDateTo) throws Exception {

        RestClient rc = getClient(sessionId);

        ListMessagesParameters params = new ListMessagesParameters();
        params.messageType = new String[]{"VoiceMail"};
        params.perPage     = 250L;           // was 100 — reduces sequential page calls by 2.5×

        if (overrideDateFrom != null && !overrideDateFrom.isBlank()) {
            params.dateFrom = overrideDateFrom;
        } else {
            String df = periodToDateFrom(period);
            if (df != null) params.dateFrom = df;
        }

        if (overrideDateTo != null && !overrideDateTo.isBlank()) {
            params.dateTo = overrideDateTo;
        }

        List<VoicemailRecord> records = new ArrayList<>();
        long page = 1;

        while (true) {
            params.page = page;

            GetMessageList response;
            if (extensionId != null && !extensionId.isBlank() && !"~".equals(extensionId)) {
                response = rc.restapi().account().extension(extensionId).messageStore().list(params);
            } else {
                response = rc.restapi().account().extension().messageStore().list(params);
            }

            if (response == null || response.records == null || response.records.length == 0) break;

            for (GetMessageInfoResponse msg : response.records) {
                VoicemailRecord rec = new VoicemailRecord();
                rec.setId(msg.id != null ? msg.id.toString() : null);

                if (msg.from != null) {
                    rec.setCallerNumber(msg.from.phoneNumber);
                    if (!nameIsPhoneNumber(msg.from.name, msg.from.phoneNumber)) {
                        rec.setCallerName(msg.from.name);
                    }
                }

                rec.setReadStatus(msg.readStatus);
                rec.setDateTime(msg.creationTime);

                if (msg.to != null && msg.to.length > 0) {
                    var to = msg.to[0];
                    rec.setExtensionId(to.extensionId != null ? to.extensionId.toString() : null);
                    rec.setExtensionName(to.name);
                }

                if (msg.attachments != null) {
                    for (var att : msg.attachments) {
                        if ("AudioTranscription".equals(att.type) && att.id != null) {
                            rec.setTranscriptAttachmentId(att.id.toString());
                        }
                        if (att.vmDuration != null) {
                            rec.setDuration(att.vmDuration.intValue());
                        }
                    }
                }

                records.add(rec);
            }

            if (response.records.length < 250) break;
            page++;
        }

        return records;
    }

    /** Convenience overload — no custom date range. */
    public List<VoicemailRecord> fetchVoicemails(
            String sessionId, String period, String extensionId) throws Exception {
        return fetchVoicemails(sessionId, period, extensionId, null, null);
    }

    // -----------------------------------------------------------------------
    // Voicemail fetching — all queues, parallelized
    // -----------------------------------------------------------------------

    public List<VoicemailRecord> fetchAllQueueVoicemails(
            String sessionId,
            String period,
            String overrideDateFrom,
            String overrideDateTo) throws Exception {

        List<ExtensionRecord> queues = fetchExtensions(sessionId).stream()
                .filter(e -> "Department".equals(e.getType()))
                .collect(Collectors.toList());

        List<CompletableFuture<List<VoicemailRecord>>> futures = new ArrayList<>();
        for (ExtensionRecord ext : queues) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<VoicemailRecord> qVMs = fetchVoicemails(
                            sessionId, period, ext.getId(), overrideDateFrom, overrideDateTo);
                    for (VoicemailRecord vm : qVMs) {
                        vm.setExtensionId(ext.getId());
                        vm.setExtensionName(ext.getName());
                        vm.setExtensionType("Department");
                    }
                    return qVMs;
                } catch (Exception e) {
                    System.err.println("[VoicemailService] Queue '" + ext.getName()
                            + "' fetch failed: " + e.getMessage());
                    return Collections.<VoicemailRecord>emptyList();
                }
            }, POOL));
        }

        List<VoicemailRecord> all = new ArrayList<>();
        for (CompletableFuture<List<VoicemailRecord>> f : futures) all.addAll(f.get());
        return all;
    }

    // -----------------------------------------------------------------------
    // Voicemail fetching — all users, parallelized
    // -----------------------------------------------------------------------

    public List<VoicemailRecord> fetchAllUserVoicemails(
            String sessionId,
            String period,
            String overrideDateFrom,
            String overrideDateTo) throws Exception {

        List<ExtensionRecord> users = fetchExtensions(sessionId).stream()
                .filter(e -> "User".equals(e.getType()))
                .collect(Collectors.toList());

        List<CompletableFuture<List<VoicemailRecord>>> futures = new ArrayList<>();
        for (ExtensionRecord ext : users) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<VoicemailRecord> uVMs = fetchVoicemails(
                            sessionId, period, ext.getId(), overrideDateFrom, overrideDateTo);
                    for (VoicemailRecord vm : uVMs) {
                        if (vm.getExtensionId()   == null) vm.setExtensionId(ext.getId());
                        if (vm.getExtensionName() == null) vm.setExtensionName(ext.getName());
                        vm.setExtensionType("User");
                    }
                    return uVMs;
                } catch (Exception e) {
                    System.err.println("[VoicemailService] User '" + ext.getName()
                            + "' fetch failed: " + e.getMessage());
                    return Collections.<VoicemailRecord>emptyList();
                }
            }, POOL));
        }

        List<VoicemailRecord> all = new ArrayList<>();
        for (CompletableFuture<List<VoicemailRecord>> f : futures) all.addAll(f.get());
        return all;
    }

    // -----------------------------------------------------------------------
    // Extensions — with 5-minute per-session cache
    // -----------------------------------------------------------------------

    /**
     * Returns the list of User and Department extensions for this account.
     *
     * <p>Results are cached per session for {@value #EXTENSIONS_CACHE_TTL_MS} ms
     * (5 minutes).  This eliminates the duplicate RC API call that previously
     * occurred on every page load: once from the frontend hitting
     * {@code /api/extensions} and once from inside the aggregate voicemail
     * endpoints.
     */
    public List<ExtensionRecord> fetchExtensions(String sessionId) throws Exception {
        // Serve from cache if still fresh
        Long cacheAge = extensionsCacheAge.get(sessionId);
        if (cacheAge != null
                && System.currentTimeMillis() - cacheAge < EXTENSIONS_CACHE_TTL_MS) {
            List<ExtensionRecord> cached = extensionsCache.get(sessionId);
            if (cached != null) return cached;
        }

        // Cache miss — fetch from RC
        RestClient rc = getClient(sessionId);

        ListExtensionsParameters params = new ListExtensionsParameters();
        params.perPage = 1000L;              // was 200 — avoids pagination for large accounts

        var response = rc.restapi().account().extension().list(params);

        List<ExtensionRecord> extensions = new ArrayList<>();
        if (response != null && response.records != null) {
            for (var ext : response.records) {
                if ("User".equals(ext.type) || "Department".equals(ext.type)) {
                    ExtensionRecord rec = new ExtensionRecord();
                    rec.setId(ext.id != null ? ext.id.toString() : null);
                    rec.setName(ext.name);
                    rec.setExtensionNumber(ext.extensionNumber);
                    rec.setType(ext.type);
                    extensions.add(rec);
                }
            }
        }

        // Store in cache
        extensionsCache.put(sessionId, extensions);
        extensionsCacheAge.put(sessionId, System.currentTimeMillis());

        return extensions;
    }

    // -----------------------------------------------------------------------
    // Callback enrichment — single bulk call-log query, matched in memory
    // -----------------------------------------------------------------------

    /**
     * Enriches the given voicemail records with callback information.
     *
     * <p>Previous approach: one call-log query per unique caller number (parallel).
     * Problem: RC's call-log endpoint is "heavy" (limit 10/60s per account), so
     * blasting 50+ parallel queries triggers 429s for all but the first 10.
     *
     * <p>New approach: ONE broad outbound call-log query covering the entire date
     * range, then index by phone number and match in memory.  Reduces API calls
     * from O(unique-numbers) to O(totalOutboundCalls / 500) ≈ 1-3.
     */
    public void enrichWithCallbacks(String sessionId, List<VoicemailRecord> records) throws Exception {
        if (records == null || records.isEmpty()) return;

        // Find the earliest voicemail timestamp to use as dateFrom
        String earliestDate = records.stream()
                .map(VoicemailRecord::getDateTime)
                .filter(d -> d != null && !d.isBlank())
                .min(Comparator.naturalOrder())
                .orElse(null);

        if (earliestDate == null) return;

        RestClient rc = getClient(sessionId);

        // ── One broad query: all outbound voice calls since earliest voicemail ──
        ReadCompanyCallLogParameters clParams = new ReadCompanyCallLogParameters();
        clParams.direction = new String[]{"Outbound"};
        clParams.type      = new String[]{"Voice"};
        clParams.dateFrom  = earliestDate;
        clParams.perPage   = 500L;

        List<CallLogRecord> allCalls = new ArrayList<>();
        long page = 1;
        while (true) {
            clParams.page = page;
            var response = rc.restapi().account().callLog().list(clParams);
            if (response == null || response.records == null
                    || response.records.length == 0) break;
            for (var r : response.records) allCalls.add(r);
            if (response.records.length < 500) break;
            page++;
        }

        System.out.println("[VoicemailService] Callback enrichment: fetched "
                + allCalls.size() + " outbound calls in " + (page) + " page(s)");

        // ── Register recordings from every outbound call ──
        for (CallLogRecord call : allCalls) {
            if (call.recording != null && call.recording.uri != null
                    && !call.recording.uri.isBlank()
                    && call.recording.contentUri != null) {
                String recUri = call.recording.uri;
                String recId  = recUri.substring(recUri.lastIndexOf('/') + 1);
                if (!recId.isBlank()) {
                    recordingStore.register(recId, call.recording.contentUri);
                }
            }
        }

        // ── Index outbound calls by normalized destination number, sorted by time ──
        Map<String, List<CallLogRecord>> callsByNumber = new java.util.HashMap<>();
        for (CallLogRecord call : allCalls) {
            if (call.to != null && call.to.phoneNumber != null) {
                String num = normalizeNumber(call.to.phoneNumber);
                callsByNumber.computeIfAbsent(num, k -> new ArrayList<>()).add(call);
            }
        }
        // Sort each bucket ascending by startTime
        callsByNumber.values().forEach(list ->
                list.sort(Comparator.comparing(c -> c.startTime)));

        // ── Match each voicemail to first outbound call to that number after VM ──
        for (VoicemailRecord vm : records) {
            if (vm.getCallerNumber() == null || vm.getDateTime() == null) continue;
            String num = normalizeNumber(vm.getCallerNumber());
            List<CallLogRecord> calls = callsByNumber.get(num);
            if (calls == null || calls.isEmpty()) continue;

            for (CallLogRecord call : calls) {
                if (call.startTime == null) continue;
                if (call.startTime.compareTo(vm.getDateTime()) <= 0) continue; // must be after VM

                vm.setCallbackTime(call.startTime);
                if (call.from != null) {
                    String name = call.from.name;
                    if (name != null && !name.isBlank()) {
                        vm.setCallbackBy(name);
                    } else if (call.from.extensionNumber != null) {
                        vm.setCallbackBy("Ext. " + call.from.extensionNumber);
                    } else {
                        vm.setCallbackBy(call.from.phoneNumber);
                    }
                }
                if (call.recording != null && call.recording.uri != null
                        && !call.recording.uri.isBlank()) {
                    String recUri = call.recording.uri;
                    String recId  = recUri.substring(recUri.lastIndexOf('/') + 1);
                    if (!recId.isBlank()) vm.setCallbackRecordingId(recId);
                }
                break; // first match wins
            }
        }
    }

    // -----------------------------------------------------------------------
    // Transcript enrichment    // -----------------------------------------------------------------------
    // Transcript enrichment — parallelized, lazy
    // -----------------------------------------------------------------------

    public void enrichWithTranscripts(String sessionId, List<VoicemailRecord> records) throws Exception {
        if (records == null || records.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (VoicemailRecord rec : records) {
            if (rec.getId() == null
                    || rec.getExtensionId() == null
                    || rec.getTranscriptAttachmentId() == null) continue;

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    RestClient rc = getClient(sessionId);

                    byte[] content = rc.restapi()
                            .account()
                            .extension(rec.getExtensionId())
                            .messageStore(rec.getId())
                            .content(rec.getTranscriptAttachmentId())
                            .get();

                    if (content != null && content.length > 0) {
                        String transcript = new String(content, StandardCharsets.UTF_8).trim();
                        rec.setTranscript(transcript);
                        String statedName = extractStatedCallerName(transcript);
                        if (statedName != null) rec.setStatedCallerName(statedName);
                    }
                } catch (Exception e) {
                    System.err.println("[VoicemailService] Transcript fetch failed for id="
                            + rec.getId() + ": " + e.getMessage());
                }
            }, POOL));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
    }

    // -----------------------------------------------------------------------
    // Current user
    // -----------------------------------------------------------------------

    public GetExtensionInfoResponse getCurrentUser(String sessionId) throws Exception {
        RestClient rc = getClient(sessionId);
        return rc.restapi().account().extension().get();
    }
}
