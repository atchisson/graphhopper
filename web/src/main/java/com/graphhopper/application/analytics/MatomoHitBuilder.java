package com.graphhopper.application.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Turns an incoming HTTP request into the set of Matomo tracking API parameters that describe it, or into
 * nothing at all when the request is not worth counting.
 * <p>
 * This holds all the decisions - what to track, how to name it, what to leave out for privacy - and knows
 * nothing about the servlet API, so it can be tested on its own.
 */
class MatomoHitBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MatomoHitBuilder.class);

    private static final Set<String> STATIC_EXTENSIONS = new HashSet<>(Arrays.asList(
            "js", "mjs", "css", "map", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif",
            "woff", "woff2", "ttf", "eot", "txt", "webmanifest", "pbf", "wasm"));

    /** First path segment -> Matomo event action, for the endpoints worth counting. */
    private static final Map<String, String> API_ENDPOINTS = Map.of(
            "route", "route",
            "route-pt", "route-pt",
            "isochrone", "isochrone",
            "spt", "spt",
            "nearest", "nearest",
            "match", "map-matching",
            "navigate", "navigate");

    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final MatomoConfig config;
    private final Set<String> trackedQueryParams;
    private final String siteUrl;
    private final String visitorIdSalt;
    private final AtomicLong hitCounter = new AtomicLong();

    MatomoHitBuilder(MatomoConfig config) {
        this.config = config;
        this.trackedQueryParams = new HashSet<>(config.getTrackedQueryParams());
        this.siteUrl = stripTrailingSlash(config.getSiteUrl());
        this.visitorIdSalt = config.getVisitorIdSalt().isEmpty() ? randomSalt() : config.getVisitorIdSalt();
        if (config.getVisitorIdSalt().isEmpty())
            logger.info("matomo.visitor_id_salt is not set, using a random salt. Set it to keep visitor identity "
                    + "stable across restarts.");
    }

    /** Everything the tracking needs to know about one request. */
    record Request(String method, String path, int status, long durationMs, Map<String, String> queryParams,
                   String clientIp, String userAgent, String acceptLanguage, String referer) {
    }

    /** The Matomo parameters describing this request, or null when it should not be tracked. */
    Map<String, String> build(Request request) {
        if (!"GET".equals(request.method()) && !"POST".equals(request.method()))
            return null;

        String path = normalizePath(request.path());
        if (isExcluded(path) || hasStaticExtension(path))
            return null;

        Map<String, String> params;
        String endpoint = apiEndpoint(path);
        if (endpoint != null) {
            if (!config.isTrackApiRequests() || !passesSampling())
                return null;
            params = apiEvent(request, endpoint);
        } else {
            String actionName = pageName(path);
            // a redirect is followed by a request for the real page, counting both would double the page views
            if (actionName == null || request.status() >= 300 && request.status() != 304)
                return null;
            params = pageView(request, actionName);
        }
        addVisitorParams(request, params);
        return params;
    }

    private boolean passesSampling() {
        return config.getApiSampleRate() >= 1.0
                || ThreadLocalRandom.current().nextDouble() < config.getApiSampleRate();
    }

    private Map<String, String> pageView(Request request, String actionName) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", trackedUrl(request));
        params.put("action_name", actionName);
        params.put("pf_srv", Long.toString(request.durationMs()));
        return params;
    }

    private Map<String, String> apiEvent(Request request, String endpoint) {
        boolean error = request.status() >= 400;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", trackedUrl(request));
        params.put("e_c", error ? "API error" : "API");
        params.put("e_a", endpoint);
        params.put("e_n", error ? "HTTP " + request.status() : profileName(request));
        params.put("e_v", Long.toString(request.durationMs()));
        // the routing endpoints are not pages, so do not let them overwrite the visitor's current page
        params.put("ca", "1");
        return params;
    }

    /** Names the event after the requested profile, which is what makes the numbers interesting. */
    private static String profileName(Request request) {
        String profile = request.queryParams().get("profile");
        if (profile == null || profile.isEmpty())
            profile = request.queryParams().get("vehicle");
        // a POST /route carries the profile in the JSON body, which we deliberately do not read
        return profile == null || profile.isEmpty() ? "unknown profile" : profile;
    }

    private void addVisitorParams(Request request, Map<String, String> params) {
        params.put("_id", visitorId(request.clientIp(), request.userAgent()));
        // cip, ua and cdt are only honoured by Matomo when a token_auth is sent along
        if (request.clientIp() != null) {
            String ip = config.isAnonymizeIp() ? anonymizeIp(request.clientIp()) : request.clientIp();
            if (ip != null)
                params.put("cip", ip);
        }
        params.put("ua", request.userAgent());
        params.put("lang", request.acceptLanguage());
        params.put("urlref", request.referer());
        params.put("cdt", Long.toString(Instant.now().getEpochSecond()));
        params.put("rand", Long.toString(hitCounter.incrementAndGet()));
    }

    /**
     * Builds the URL reported to Matomo. Only the whitelisted query parameters are kept, so coordinates and
     * other personal data are never sent to the analytics backend.
     */
    String trackedUrl(Request request) {
        StringBuilder sb = new StringBuilder(siteUrl).append(request.path());
        Map<String, String> kept = new TreeMap<>();
        for (Map.Entry<String, String> e : request.queryParams().entrySet()) {
            if (trackedQueryParams.contains(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty())
                kept.put(e.getKey(), e.getValue());
        }
        boolean first = true;
        for (Map.Entry<String, String> e : kept.entrySet()) {
            sb.append(first ? '?' : '&').append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** Groups the requests of one visitor for one UTC day without storing anything identifying. */
    String visitorId(String ip, String userAgent) {
        String raw = visitorIdSalt + "|" + (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent)
                + "|" + DAY_FORMAT.format(Instant.now());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            // Matomo expects exactly 16 hexadecimal characters
            return toHex(Arrays.copyOf(digest, 8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Drops the last octet of an IPv4 address, the last 80 bits of an IPv6 one. */
    static String anonymizeIp(String ip) {
        if (ip == null || !isIpLiteral(ip))
            return null;
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            int zeroed = bytes.length == 4 ? 1 : 10;
            for (int i = bytes.length - zeroed; i < bytes.length; i++)
                bytes[i] = 0;
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    /**
     * Whether this is a literal IP address. X-Forwarded-For is client controlled, so we must never hand it to
     * {@link InetAddress#getByName} unchecked - a host name in there would turn every request into a DNS lookup.
     */
    private static boolean isIpLiteral(String ip) {
        if (IPV4.matcher(ip).matches())
            return true;
        // a string containing a colon is parsed as an IPv6 literal and never resolved
        return ip.indexOf(':') >= 0
                && ip.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.' || c == '%');
    }

    private boolean isExcluded(String path) {
        for (String prefix : config.getExcludedPaths()) {
            if (path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"))
                return true;
        }
        return false;
    }

    static boolean hasStaticExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash)
            return false;
        return STATIC_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String apiEndpoint(String path) {
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        int slash = withoutLeadingSlash.indexOf('/');
        String firstSegment = slash < 0 ? withoutLeadingSlash : withoutLeadingSlash.substring(0, slash);
        return API_ENDPOINTS.get(firstSegment);
    }

    /** The human readable name of a UI page, or null when the path is not one of the UI entry points. */
    static String pageName(String path) {
        switch (path) {
            case "":
            case "/":
            case "/maps":
            case "/maps/index.html":
                return "Maps";
            case "/maps/isochrone":
            case "/maps/isochrone/index.html":
                return "Maps / Isochrone";
            case "/maps/pt":
            case "/maps/pt/index.html":
                return "Maps / Public transit";
            case "/maps/map-matching":
            case "/maps/map-matching/index.html":
                return "Maps / Map matching";
            default:
                return null;
        }
    }

    static String normalizePath(String uri) {
        if (uri == null || uri.isEmpty())
            return "/";
        return uri.length() > 1 ? stripTrailingSlash(uri) : uri;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static List<String> supportedEndpoints() {
        return List.copyOf(new TreeMap<>(API_ENDPOINTS).keySet());
    }
}
