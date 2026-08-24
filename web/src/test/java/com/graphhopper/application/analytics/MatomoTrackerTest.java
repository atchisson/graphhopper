package com.graphhopper.application.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatomoTrackerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FAST_FLUSH = ",\"flush_interval_ms\":200";

    private HttpServer server;
    private MatomoTracker tracker;
    private final BlockingQueue<String> received = new ArrayBlockingQueue<>(64);
    private final AtomicInteger responseStatus = new AtomicInteger(204);
    private final AtomicBoolean stall = new AtomicBoolean(false);

    private MatomoConfig startServerAndBuildConfig(String extraJson) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/matomo.php", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (stall.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = responseStatus.get();
            byte[] body = new byte[0];
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String json = "{\"enabled\":true,\"site_id\":7,\"token_auth\":\"secret-token\","
                + "\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/matomo.php\","
                + "\"request_timeout_ms\":2000,\"connect_timeout_ms\":2000"
                + extraJson + "}";
        return MAPPER.readValue(json, MatomoConfig.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tracker != null)
            tracker.stop();
        if (server != null)
            server.stop(0);
    }

    private static Map<String, String> hit(String actionName) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action_name", actionName);
        params.put("url", "https://maps.example.com/maps/");
        return params;
    }

    @Test
    void hitsAreSentToMatomoAsABulkRequest() throws Exception {
        tracker = new MatomoTracker(startServerAndBuildConfig(FAST_FLUSH));
        tracker.start();
        tracker.track(hit("Maps"));
        tracker.track(hit("Maps / Isochrone"));

        JsonNode payload = MAPPER.readTree(awaitPayload());
        assertEquals("secret-token", payload.get("token_auth").asText());
        JsonNode requests = payload.get("requests");
        assertTrue(requests.size() >= 1, "expected at least one hit, got " + requests);
        String first = requests.get(0).asText();
        assertTrue(first.startsWith("?idsite=7&rec=1&apiv=1&send_image=0"), "unexpected hit: " + first);
        assertTrue(first.contains("action_name=Maps"), "unexpected hit: " + first);
        // the URL must be percent-encoded so Matomo does not read it as more parameters
        assertTrue(first.contains("url=https%3A%2F%2Fmaps.example.com%2Fmaps%2F"), "unexpected hit: " + first);
    }

    @Test
    void burstsAreBatchedIntoASingleRequest() throws Exception {
        tracker = new MatomoTracker(startServerAndBuildConfig(",\"batch_size\":10,\"flush_interval_ms\":1000"));
        tracker.start();
        for (int i = 0; i < 10; i++)
            tracker.track(hit("Maps"));

        JsonNode payload = MAPPER.readTree(awaitPayload());
        assertEquals(10, payload.get("requests").size());
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "the batch should have been sent in one request");
    }

    @Test
    void emptyValuesAreLeftOutOfTheHit() throws Exception {
        tracker = new MatomoTracker(startServerAndBuildConfig(FAST_FLUSH));
        tracker.start();
        Map<String, String> params = hit("Maps");
        params.put("urlref", null);
        params.put("lang", "");
        tracker.track(params);

        String first = MAPPER.readTree(awaitPayload()).get("requests").get(0).asText();
        assertTrue(!first.contains("urlref") && !first.contains("lang"), "unexpected hit: " + first);
    }

    @Test
    void matomoErrorsAreCountedAndDoNotBreakTheTracker() throws Exception {
        responseStatus.set(500);
        tracker = new MatomoTracker(startServerAndBuildConfig(FAST_FLUSH));
        tracker.start();
        tracker.track(hit("Maps"));
        assertNotNull(awaitPayload());

        responseStatus.set(204);
        tracker.track(hit("Maps"));
        assertNotNull(awaitPayload());
        waitFor(() -> tracker.getSentCount() == 1 && tracker.getFailedCount() == 1);
    }

    @Test
    void hitsAreDroppedRatherThanBlockingWhenTheQueueIsFull() throws Exception {
        // the stub takes its time answering, so the sender thread is busy and the queue fills up
        MatomoConfig config = startServerAndBuildConfig(",\"queue_capacity\":2,\"batch_size\":1,\"flush_interval_ms\":200");
        stall.set(true);
        tracker = new MatomoTracker(config);
        tracker.start();
        for (int i = 0; i < 200; i++)
            tracker.track(hit("Maps"));
        waitFor(() -> tracker.getDroppedCount() > 0);
        assertTrue(tracker.getDroppedCount() > 0, "expected hits to be dropped, not queued forever");
    }

    @Test
    void nothingIsSentBeforeStart() {
        tracker = new MatomoTracker(new MatomoConfig());
        tracker.track(hit("Maps"));
        assertEquals(0, tracker.getSentCount());
        assertEquals(0, tracker.getDroppedCount());
    }

    private String awaitPayload() throws InterruptedException {
        String payload = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(payload, "Matomo stub did not receive anything");
        return payload;
    }

    private static void waitFor(BooleanCondition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isMet())
                return;
            Thread.sleep(20);
        }
        assertTrue(condition.isMet(), "condition was not met in time");
    }

    private interface BooleanCondition {
        boolean isMet();
    }
}
