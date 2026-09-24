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
    // Callback enrichment — parallelized by unique caller number
    // -----------------------------------------------------------------------

    public void enrichWithCallbacks(String sessionId, List<VoicemailRecord> records) throws Exception {
        if (records == null || records.isEmpty()) return;

        Map<String, List<VoicemailRecord>> byNumber = new LinkedHashMap<>();
        for (VoicemailRecord rec : records) {
            if (rec.getCallerNumber() == null || rec.getDateTime() == null) continue;
            String key = normalizeNumber(rec.getCallerNumber());
            byNumber.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, List<VoicemailRecord>> entry : byNumber.entrySet()) {
            String number             = entry.getKey();
            List<VoicemailRecord> vmGroup = entry.getValue();

            futures.add(CompletableFuture.runAsync(() -> {
                vmGroup.sort((a, b) -> {
                    String da = a.getDateTime() != null ? a.getDateTime() : "";
                    String db = b.getDateTime() != null ? b.getDateTime() : "";
                    return da.compareTo(db);
                });

                try {
                    RestClient rc = getClient(sessionId);

                    ReadCompanyCallLogParameters clParams = new ReadCompanyCallLogParameters();
                    clParams.direction   = new String[]{"Outbound"};
                    clParams.type        = new String[]{"Voice"};
                    clParams.phoneNumber = number;
                    clParams.dateFrom    = vmGroup.get(0).getDateTime();
                    clParams.perPage     = 100L;

                    var clResponse = rc.restapi().account().callLog().list(clParams);

                    if (clResponse == null || clResponse.records == null
                            || clResponse.records.length == 0) return;

                    Arrays.sort(clResponse.records, Comparator.comparing(c -> c.startTime));

                    int callIdx = 0;
                    for (int i = 0; i < vmGroup.size(); i++) {
                        VoicemailRecord vm = vmGroup.get(i);
                        String windowEnd = (i + 1 < vmGroup.size())
                                ? vmGroup.get(i + 1).getDateTime() : null;

                        while (callIdx < clResponse.records.length
                                && clResponse.records[callIdx].startTime
                                        .compareTo(vm.getDateTime()) <= 0) {
                            callIdx++;
                        }
                        if (callIdx >= clResponse.records.length) break;

                        var call = clResponse.records[callIdx];
                        if (windowEnd != null && call.startTime.compareTo(windowEnd) >= 0) continue;

                        vm.setCallbackTime(call.startTime);
                        if (call.from != null) {
                            String agentName = call.from.name;
                            if (agentName != null && !agentName.isBlank()) {
                                vm.setCallbackBy(agentName);
                            } else if (call.from.extensionNumber != null) {
                                vm.setCallbackBy("Ext. " + call.from.extensionNumber);
                            } else {
                                vm.setCallbackBy(call.from.phoneNumber);
                            }
                        }
                        // Capture recording ID if this callback call was recorded
                        if (call.recording != null && call.recording.id != null) {
                            vm.setCallbackRecordingId(call.recording.id.toString());
                        }
                        callIdx++;
                    }

                } catch (Exception e) {
                    System.err.println("[VoicemailService] Callback lookup failed for "
                            + number + ": " + e.getMessage());
                }
            }, POOL));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
    }

    // -----------------------------------------------------------------------
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
