package com.graphhopper.application.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatomoHitBuilderTest {

    private static MatomoConfig config(String extraJson) {
        String json = "{\"enabled\":true,\"url\":\"http://localhost/matomo.php\",\"token_auth\":\"t\","
                + "\"site_url\":\"https://maps.example.com/\"" + extraJson + "}";
        try {
            return new ObjectMapper().readValue(json, MatomoConfig.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static MatomoHitBuilder builder() {
        return new MatomoHitBuilder(config(",\"visitor_id_salt\":\"salt\""));
    }

    private static MatomoHitBuilder.Request get(String path, int status, Map<String, String> query) {
        return new MatomoHitBuilder.Request("GET", path, status, 42, query, "203.0.113.42",
                "Mozilla/5.0 Firefox/128.0", "fr-FR,fr;q=0.9", "https://news.ycombinator.com/");
    }

    @Test
    void mapsPageIsTrackedAsPageView() {
        Map<String, String> hit = builder().build(get("/maps/", 200, Map.of()));
        assertNotNull(hit);
        assertEquals("Maps", hit.get("action_name"));
        assertEquals("https://maps.example.com/maps/", hit.get("url"));
        assertEquals("42", hit.get("pf_srv"));
        assertNull(hit.get("e_c"), "a page view must not be sent as an event");
    }

    @Test
    void subPagesGetTheirOwnName() {
        assertEquals("Maps / Isochrone", builder().build(get("/maps/isochrone/", 200, Map.of())).get("action_name"));
        assertEquals("Maps / Public transit", builder().build(get("/maps/pt/", 200, Map.of())).get("action_name"));
    }

    @Test
    void routeRequestIsTrackedAsEventNamedAfterTheProfile() {
        Map<String, String> hit = builder().build(get("/route", 200, Map.of("profile", "bike", "point", "48.8,2.3")));
        assertNotNull(hit);
        assertEquals("API", hit.get("e_c"));
        assertEquals("route", hit.get("e_a"));
        assertEquals("bike", hit.get("e_n"));
        assertEquals("42", hit.get("e_v"));
    }

    @Test
    void coordinatesAreNeverReportedToMatomo() {
        Map<String, String> hit = builder().build(get("/route", 200,
                Map.of("profile", "car", "point", "48.85,2.35", "point_hint", "Rue de Rivoli")));
        assertEquals("https://maps.example.com/route?profile=car", hit.get("url"));
    }

    @Test
    void failedRequestsAreTrackedSeparately() {
        Map<String, String> hit = builder().build(get("/route", 400, Map.of("profile", "car")));
        assertEquals("API error", hit.get("e_c"));
        assertEquals("HTTP 400", hit.get("e_n"));
    }

    @Test
    void postWithoutQueryParamsHasNoKnownProfile() {
        MatomoHitBuilder.Request post = new MatomoHitBuilder.Request("POST", "/route", 200, 12, Map.of(),
                "203.0.113.42", "curl/8.0", null, null);
        assertEquals("unknown profile", builder().build(post).get("e_n"));
    }

    @Test
    void staticAssetsAndTilesAndHealthChecksAreIgnored() {
        MatomoHitBuilder b = builder();
        assertNull(b.build(get("/maps/config.js", 200, Map.of())));
        assertNull(b.build(get("/maps/assets/logo.svg", 200, Map.of())));
        assertNull(b.build(get("/mvt/14/8000/5000.mvt", 200, Map.of())));
        assertNull(b.build(get("/health", 200, Map.of())));
        assertNull(b.build(get("/i18n/fr", 200, Map.of())));
        assertNull(b.build(get("/webjars/leaflet/leaflet.js", 200, Map.of())));
        assertNull(b.build(get("/favicon.ico", 200, Map.of())));
    }

    @Test
    void unknownPathsAreIgnored() {
        assertNull(builder().build(get("/some/other/thing", 200, Map.of())));
    }

    @Test
    void redirectsAreNotCountedBecauseTheTargetPageIsCountedItself() {
        // GET / answers 303 towards /maps/, tracking both would double every visit
        assertNull(builder().build(get("/", 303, Map.of())));
        assertNotNull(builder().build(get("/", 200, Map.of())));
    }

    @Test
    void headAndOptionsAreIgnored() {
        MatomoHitBuilder b = builder();
        for (String method : new String[]{"HEAD", "OPTIONS", "PUT"}) {
            MatomoHitBuilder.Request req = new MatomoHitBuilder.Request(method, "/route", 200, 1,
                    Map.of("profile", "car"), "203.0.113.42", "curl/8.0", null, null);
            assertNull(b.build(req), method + " must not be tracked");
        }
    }

    @Test
    void apiEventsCanBeTurnedOffWithoutLosingPageViews() {
        MatomoHitBuilder b = new MatomoHitBuilder(config(",\"track_api_requests\":false"));
        assertNull(b.build(get("/route", 200, Map.of("profile", "car"))));
        assertNotNull(b.build(get("/maps/", 200, Map.of())));
    }

    @Test
    void zeroSampleRateDropsApiEventsButKeepsPageViews() {
        MatomoHitBuilder b = new MatomoHitBuilder(config(",\"api_sample_rate\":0.0"));
        for (int i = 0; i < 50; i++)
            assertNull(b.build(get("/route", 200, Map.of("profile", "car"))));
        assertNotNull(b.build(get("/maps/", 200, Map.of())));
    }

    @Test
    void visitorIpIsAnonymizedByDefault() {
        assertEquals("203.0.113.0", builder().build(get("/maps/", 200, Map.of())).get("cip"));
        assertEquals("203.0.113.42", new MatomoHitBuilder(config(",\"anonymize_ip\":false"))
                .build(get("/maps/", 200, Map.of())).get("cip"));
    }

    @Test
    void anonymizeIpDropsTheHostPart() {
        assertEquals("192.168.1.0", MatomoHitBuilder.anonymizeIp("192.168.1.77"));
        assertEquals("2001:db8:1234:0:0:0:0:0", MatomoHitBuilder.anonymizeIp("2001:db8:1234:5678:9abc:def0:1234:5678"));
        // a host name in X-Forwarded-For must be rejected outright rather than resolved
        assertNull(MatomoHitBuilder.anonymizeIp("not-an-ip-at-all.invalid"));
        assertNull(MatomoHitBuilder.anonymizeIp("localhost"));
        assertNull(MatomoHitBuilder.anonymizeIp(null));
    }

    @Test
    void visitorIdIsSixteenHexCharsAndStableForTheSameVisitor() {
        MatomoHitBuilder b = builder();
        String id = b.visitorId("203.0.113.42", "Firefox");
        assertTrue(id.matches("[0-9a-f]{16}"), "unexpected visitor id: " + id);
        assertEquals(id, b.visitorId("203.0.113.42", "Firefox"));
        assertNotEquals(id, b.visitorId("203.0.113.43", "Firefox"));
        assertNotEquals(id, b.visitorId("203.0.113.42", "Chrome"));
        // a different install must not produce the same ids for the same visitor
        assertNotEquals(id, new MatomoHitBuilder(config(",\"visitor_id_salt\":\"other\"")).visitorId("203.0.113.42", "Firefox"));
    }

    @Test
    void userAgentAndLanguageAreForwardedSoMatomoCanClassifyTheVisit() {
        Map<String, String> hit = builder().build(get("/maps/", 200, Map.of()));
        assertEquals("Mozilla/5.0 Firefox/128.0", hit.get("ua"));
        assertEquals("fr-FR,fr;q=0.9", hit.get("lang"));
        assertEquals("https://news.ycombinator.com/", hit.get("urlref"));
    }

    @Test
    void pathsAreNormalizedBeforeBeingClassified() {
        assertEquals("Maps", MatomoHitBuilder.pageName(MatomoHitBuilder.normalizePath("/maps/")));
        assertEquals("route", MatomoHitBuilder.apiEndpoint("/route"));
        assertEquals("navigate", MatomoHitBuilder.apiEndpoint("/navigate/directions/v5/gh/car/x"));
        assertNull(MatomoHitBuilder.apiEndpoint("/maps/pt"));
        assertTrue(MatomoHitBuilder.hasStaticExtension("/maps/main.WOFF2"));
        assertTrue(MatomoHitBuilder.hasStaticExtension("/maps/vendor.js.map"));
        assertFalse(MatomoHitBuilder.hasStaticExtension("/maps/isochrone"));
    }
}
