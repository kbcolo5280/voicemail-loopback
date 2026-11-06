package com.voicemail.tracker;

import com.ringcentral.RestClient;
import com.ringcentral.definitions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
     * Application-level cache for the extensions list.  Keyed at the app level
     * (not per session) because extensions are account-wide and shared across
     * all logged-in users.  Using a per-session cache meant every new HTTP
     * session (page reload, new tab, 401-triggered reload) caused a cold RC
     * API call, which produced 429 storms during active testing.
     *
     * TTL is 1 hour — extensions change very rarely (staff additions) and a
     * stale list for up to 60 minutes is acceptable.
     */
    private static final long               EXTENSIONS_CACHE_TTL_MS = 60 * 60 * 1_000L;
    private volatile List<ExtensionRecord>  extensionsCacheData      = null;
    private volatile long                   extensionsCacheAge       = 0L;

    /**
     * Evicts session-specific state (token-refresh lock) for the given session.
     * Called on logout so memory is reclaimed promptly.
     * Extensions cache is application-level and intentionally not cleared here.
     */
    public void clearSessionCache(String sessionId) {
        sessionLocks.remove(sessionId);
    }

    /** Force-clears the application-level extensions cache (e.g. after adding a new user). */
    public void clearExtensionsCache() {
        extensionsCacheData = null;
        extensionsCacheAge  = 0L;
    }

    // -----------------------------------------------------------------------
    // Stated-name pattern
    // -----------------------------------------------------------------------

    // Matches caller self-identifications in voicemail transcripts.
    // Natural speech phrases: "this is John", "my name is Jane Smith", "it's Mike", "I'm Sarah".
    // AI Receptionist label format: "Caller: John Smith", "caller is Jane", "caller's name is Mike".
    // The (?<=\s) alternative was intentionally removed — it was too broad with CASE_INSENSITIVE
    // and captured words like "yeah" before reaching the actual name.
    private static final Pattern STATED_NAME_PATTERN = Pattern.compile(
            "(?:this is|my name is|it'?s|i'?m|caller(?:'s\\s+name)?\\s*(?:is|:))\\s+" +
            "([A-Z][a-zA-Z'-]+(?:\\s+(?!(?:And|Or|But|To|In|At|For|With|From|Just|The|A|An|Of|On|Up|So|About|Calling|Reaching|Following|Trying|Getting)\\b)[A-Z][a-zA-Z'-]+){0,2})",
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

    /**
     * Composite key used for same-caller grouping.
     * Uses "number|statedCallerName" when a transcript name is available,
     * so different people calling from the same shared number are counted separately.
     * Falls back to number-only when no stated name exists.
     */
    private String callerGroupKey(VoicemailRecord r) {
        String num  = normalizeNumber(r.getCallerNumber());
        String name = r.getStatedCallerName();
        if (name != null && !name.isBlank()) {
            return num + "|" + name.toLowerCase().trim();
        }
        return num != null ? num : "";
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
                    // Log attachment types for debugging (helps diagnose AI Receptionist
                    // transferred VMs that have no transcript — look for unexpected types
                    // in Render logs, e.g. "Audio" only, never "AudioTranscription").
                    java.util.StringJoiner attachTypes = new java.util.StringJoiner(",");
                    for (var att : msg.attachments) {
                        if (att.type != null) attachTypes.add(att.type);
                    }
                    if (!attachTypes.toString().contains("AudioTranscription")) {
                        System.out.println("[VoicemailService] No AudioTranscription attachment for id="
                                + msg.id + " from=" + (msg.from != null ? msg.from.phoneNumber : "?")
                                + " attachmentTypes=[" + attachTypes + "]");
                    }
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
     * <p>Results are cached at the application level for {@value #EXTENSIONS_CACHE_TTL_MS} ms
     * (5 minutes).  This eliminates the duplicate RC API call that previously
     * occurred on every page load: once from the frontend hitting
     * {@code /api/extensions} and once from inside the aggregate voicemail
     * endpoints.
     */
    public List<ExtensionRecord> fetchExtensions(String sessionId) throws Exception {
        // Serve from application-level cache if still fresh (1-hour TTL).
        // Cache is shared across all sessions — extensions are account-wide and
        // change very rarely, so a single app-level copy is safe and eliminates
        // the 429 storm that occurred when per-session caches all started cold.
        List<ExtensionRecord> cached = extensionsCacheData;
        if (cached != null
                && System.currentTimeMillis() - extensionsCacheAge < EXTENSIONS_CACHE_TTL_MS) {
            return cached;
        }

        // Cache miss — fetch from RC
        RestClient rc = getClient(sessionId);

        ListExtensionsParameters params = new ListExtensionsParameters();
        params.perPage = 1000L;              // avoids pagination for large accounts

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

        // Store in application-level cache
        extensionsCacheData = extensions;
        extensionsCacheAge  = System.currentTimeMillis();

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
                .map(r -> r.getDateTime())
                .filter(d -> d != null && !d.isBlank())
                .min(Comparator.naturalOrder())
                .orElse(null);

        if (earliestDate == null) return;

        RestClient rc = getClient(sessionId);

        // ── Build extensionId → display number map from cached extension list ──
        // RC call log only returns extensionId in call.from, not the short number.
        // fetchExtensions() is cached (1-hour TTL) so this adds no extra RC call.
        Map<String, String> extIdToNumber = new java.util.HashMap<>();
        try {
            for (ExtensionRecord er : fetchExtensions(sessionId)) {
                if (er.getId() != null && er.getExtensionNumber() != null) {
                    extIdToNumber.put(er.getId(), er.getExtensionNumber());
                }
            }
        } catch (Exception e) {
            System.err.println("[VoicemailService] Could not load extensions for callbackBy lookup: " + e.getMessage());
        }

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

        // ── Deduplicate by sessionId ──────────────────────────────────────────────
        // RC sometimes logs multiple call-legs of one physical outbound call as
        // separate entries with slightly different startTimes.  Keeping only the
        // first entry per sessionId ensures a single physical call can only be
        // claimed by one callerGroup.
        {
            java.util.LinkedHashMap<String, CallLogRecord> seen = new java.util.LinkedHashMap<>();
            for (CallLogRecord call : allCalls) {
                String key = (call.sessionId != null && !call.sessionId.isBlank())
                        ? call.sessionId : call.startTime;
                seen.putIfAbsent(key, call);
            }
            int before = allCalls.size();
            allCalls = new ArrayList<>(seen.values());
            int after  = allCalls.size();
            if (before != after)
                System.out.println("[VoicemailService] Deduped calls by sessionId: "
                        + before + " → " + after);
        }

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

        // ── Match callbacks per callerGroup (number + statedCallerName) ──────────
        // Each callerGroup consumes its own callback call independently, so two
        // different people calling from the same shared number each get matched to
        // the correct callback rather than sharing one.  All VMs within a group
        // that pre-date the callback receive credit; the recording is attached only
        // to the most-recent VM in the group (the one most likely to have triggered
        // the callback).  Bug fixes applied here:
        //   • Bug 1: per-group consumption prevents different callers sharing one callback
        //   • Bug 2: callbackBy includes extension number, e.g. "Ken B (101)"
        Map<String, List<VoicemailRecord>> vmsByGroup = records.stream()
                .filter(r -> r.getCallerNumber() != null && r.getDateTime() != null)
                .collect(Collectors.groupingBy(r -> callerGroupKey(r)));

        // Track which calls have been claimed (num|startTime) so groups from the
        // same phone number each consume a distinct outbound call.
        java.util.Set<String> claimedCalls = new java.util.HashSet<>();

        // Process groups in newest-VM-first order so the most recent caller's group
        // claims the first available callback call. When two different people call
        // from the same shared number, the callback is most likely for the newest VM.
        vmsByGroup.entrySet().stream()
                .sorted(Comparator.comparing((java.util.Map.Entry<String, List<VoicemailRecord>> e) ->
                        e.getValue().stream()
                        .map(vm -> vm.getDateTime() != null ? vm.getDateTime() : "")
                        .max(Comparator.naturalOrder()).orElse("")).reversed())
                .forEach(entry -> {
                    List<VoicemailRecord> groupVMs = entry.getValue();
                    String num = normalizeNumber(groupVMs.get(0).getCallerNumber());
                    List<CallLogRecord> calls = callsByNumber.get(num);
                    if (calls == null || calls.isEmpty()) return;

                    // Sort group VMs oldest-first; the last element is the most recent
                    groupVMs.sort(Comparator.comparing(
                            (VoicemailRecord vm) -> vm.getDateTime() != null ? vm.getDateTime() : "",
                            Comparator.naturalOrder()));
                    VoicemailRecord mostRecentVM = groupVMs.get(groupVMs.size() - 1);

                    for (CallLogRecord call : calls) {
                        if (call.startTime == null) continue;
                        String claimKey = num + "|" + call.startTime;
                        if (claimedCalls.contains(claimKey)) continue; // already used by another group

                        // Call must come after at least one VM in this group
                        boolean coversAny = groupVMs.stream()
                                .anyMatch(vm -> call.startTime.compareTo(vm.getDateTime()) > 0);
                        if (!coversAny) continue;

                        // Claim this call for this group
                        claimedCalls.add(claimKey);

                        // Build "Called Back By" string — include display extension number.
                        // RC call log returns extensionId (internal long ID), not the short
                        // display number.  We resolve it via the cached extension list.
                        String callbackBy = null;
                        if (call.from != null) {
                            String name    = call.from.name;
                            String extId   = call.from.extensionId != null
                                    ? call.from.extensionId.toString() : null;
                            String extNum  = extId != null ? extIdToNumber.get(extId) : null;
                            if (name != null && !name.isBlank()) {
                                callbackBy = (extNum != null && !extNum.isBlank())
                                        ? name + " (" + extNum + ")"
                                        : name;
                            } else if (extNum != null && !extNum.isBlank()) {
                                callbackBy = "Ext. " + extNum;
                            } else if (call.from.phoneNumber != null) {
                                callbackBy = call.from.phoneNumber;
                            }
                        }

                        // Extract recording ID once
                        String recId = null;
                        if (call.recording != null && call.recording.uri != null
                                && !call.recording.uri.isBlank()) {
                            String recUri = call.recording.uri;
                            String rid = recUri.substring(recUri.lastIndexOf('/') + 1);
                            if (!rid.isBlank()) recId = rid;
                        }

                        // Credit all VMs in this group that pre-date the callback
                        for (VoicemailRecord vm : groupVMs) {
                            if (vm.getDateTime() == null) continue;
                            if (call.startTime.compareTo(vm.getDateTime()) <= 0) continue;
                            vm.setCallbackTime(call.startTime);
                            if (callbackBy != null) vm.setCallbackBy(callbackBy);
                        }

                        // Recording only on the most-recent VM (avoids same recording
                        // appearing on every VM from a shared-number group)
                        if (recId != null
                                && mostRecentVM.getDateTime() != null
                                && call.startTime.compareTo(mostRecentVM.getDateTime()) > 0) {
                            mostRecentVM.setCallbackRecordingId(recId);
                        }
                        break; // one callback per group
                    }
                });

        // ── Set same-caller count on each record ──────────────────────────────
        // Groups by number+statedCallerName when a transcript name is available,
        // so two different people calling from the same number are counted separately.
        // Falls back to number-only when no transcript name exists.
        Map<String, Long> countByGroup = records.stream()
                .filter(r -> r.getCallerNumber() != null)
                .collect(Collectors.groupingBy(
                        r -> callerGroupKey(r),
                        Collectors.counting()));
        for (VoicemailRecord vm : records) {
            if (vm.getCallerNumber() != null) {
                long count = countByGroup.getOrDefault(callerGroupKey(vm), 1L);
                vm.setSameCallerCount((int) count);
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
                        if (statedName != null) {
                            rec.setStatedCallerName(statedName);
                        } else {
                            // Log first 200 chars so we can tune the name-extraction
                            // pattern for AI Receptionist or other unusual formats.
                            System.out.println("[VoicemailService] No statedName extracted for id="
                                    + rec.getId() + " caller=" + rec.getCallerNumber()
                                    + " transcript(200)=" + transcript.substring(0, Math.min(200, transcript.length())));
                        }
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
