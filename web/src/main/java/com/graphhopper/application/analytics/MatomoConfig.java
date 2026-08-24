package com.graphhopper.application.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration of the server-side Matomo tracking, read from the {@code matomo} block of config.yml.
 * <p>
 * Everything is tracked from the server via the Matomo HTTP Tracking API, so no JavaScript is involved
 * and content blockers cannot suppress it.
 */
public class MatomoConfig {

    @JsonProperty
    private boolean enabled = false;

    /** Full URL of the tracking endpoint, e.g. {@code https://analytics.example.com/matomo.php}. */
    @JsonProperty
    private String url = "";

    /** The Matomo site id ("idsite"). */
    @JsonProperty("site_id")
    private int siteId = 1;

    /**
     * A Matomo API auth token. Required: without it Matomo refuses the {@code cip}, {@code ua} and
     * {@code cdt} overrides, which means every visitor would be attributed to this server's own IP.
     */
    @JsonProperty("token_auth")
    private String tokenAuth = "";

    /** Base URL used to build the page URLs reported to Matomo, e.g. {@code https://maps.example.com}. */
    @JsonProperty("site_url")
    private String siteUrl = "http://localhost:8989";

    /** Zero the last octet (IPv4) / last 80 bits (IPv6) before sending the IP to Matomo. */
    @JsonProperty("anonymize_ip")
    private boolean anonymizeIp = true;

    /** Take the client IP from the X-Forwarded-For header. Enable this only behind a trusted reverse proxy. */
    @JsonProperty("trust_forwarded_for")
    private boolean trustForwardedFor = false;

    /**
     * Salt for the pseudonymous visitor id. Set it to any random string to keep visitors stable across
     * restarts; if left empty a random salt is generated at startup.
     */
    @JsonProperty("visitor_id_salt")
    private String visitorIdSalt = "";

    /** Track the routing endpoints (/route, /isochrone, ...) as Matomo events, not just page views. */
    @JsonProperty("track_api_requests")
    private boolean trackApiRequests = true;

    /** Fraction of API requests to report, between 0 and 1. Page views are never sampled. */
    @JsonProperty("api_sample_rate")
    private double apiSampleRate = 1.0;

    /** Requests whose path starts with one of these prefixes are never tracked. */
    @JsonProperty("excluded_paths")
    private List<String> excludedPaths = Arrays.asList(
            "/health", "/healthcheck", "/info", "/i18n", "/mvt", "/pt-mvt", "/webjars", "/favicon.ico");

    /** Query parameters kept in the reported page URL. Anything else (in particular coordinates) is dropped. */
    @JsonProperty("tracked_query_params")
    private List<String> trackedQueryParams = Arrays.asList(
            "profile", "vehicle", "weighting", "algorithm", "locale", "layer", "elevation", "type");

    /** Maximum number of pending hits held in memory. Once full, new hits are dropped instead of blocking. */
    @JsonProperty("queue_capacity")
    private int queueCapacity = 10_000;

    /** Number of hits sent per Matomo bulk request. */
    @JsonProperty("batch_size")
    private int batchSize = 50;

    /** Send a partially filled batch after this many milliseconds. */
    @JsonProperty("flush_interval_ms")
    private int flushIntervalMs = 5_000;

    @JsonProperty("connect_timeout_ms")
    private int connectTimeoutMs = 5_000;

    @JsonProperty("request_timeout_ms")
    private int requestTimeoutMs = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public int getSiteId() {
        return siteId;
    }

    public String getTokenAuth() {
        return tokenAuth == null ? "" : tokenAuth;
    }

    public String getSiteUrl() {
        return siteUrl == null ? "" : siteUrl;
    }

    public boolean isAnonymizeIp() {
        return anonymizeIp;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public String getVisitorIdSalt() {
        return visitorIdSalt == null ? "" : visitorIdSalt;
    }

    public boolean isTrackApiRequests() {
        return trackApiRequests;
    }

    public double getApiSampleRate() {
        return apiSampleRate;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public List<String> getTrackedQueryParams() {
        return trackedQueryParams;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }
}
