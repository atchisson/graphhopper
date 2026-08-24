package com.graphhopper.application.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends hits to the Matomo HTTP Tracking API from a single background thread.
 * <p>
 * {@link #track} never blocks and never throws: hits are queued and dropped once the queue is full, so a slow
 * or unreachable Matomo instance can never slow down or break routing requests.
 */
public class MatomoTracker implements Managed {

    private static final Logger logger = LoggerFactory.getLogger(MatomoTracker.class);
    private static final long ERROR_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final MatomoConfig config;
    private final BlockingQueue<String> queue;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean running;
    private HttpClient httpClient;
    private Thread sender;
    private long lastErrorLogNanos = 0;

    public MatomoTracker(MatomoConfig config) {
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity()));
    }

    @Override
    public void start() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        running = true;
        sender = new Thread(this::run, "matomo-tracker");
        sender.setDaemon(true);
        sender.start();
        logger.info("Matomo server-side tracking enabled, endpoint={} idsite={}", config.getUrl(), config.getSiteId());
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (sender != null) {
            sender.join(config.getRequestTimeoutMs() + 2000L);
            if (sender.isAlive())
                sender.interrupt();
        }
        logger.info("Matomo tracking stopped, {} hits sent, {} dropped (queue full), {} failed",
                sent.get(), dropped.get(), failed.get());
    }

    /**
     * Queues one hit. The map holds Matomo tracking API parameters, {@code idsite} and {@code rec} are added here.
     */
    public void track(Map<String, String> params) {
        if (!running)
            return;
        if (!queue.offer(buildQueryString(params)))
            dropped.incrementAndGet();
    }

    String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("?idsite=").append(config.getSiteId()).append("&rec=1&apiv=1&send_image=0");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty())
                continue;
            sb.append("&").append(e.getKey()).append("=").append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void run() {
        List<String> batch = new ArrayList<>(config.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                String first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null)
                    continue;
                batch.add(first);
                collectBatch(batch);
                send(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                logThrottled("unexpected error while sending hits to Matomo", ex);
            } finally {
                batch.clear();
            }
        }
    }

    /** Waits up to the flush interval for the batch to fill up, so bursts of requests are sent in one call. */
    private void collectBatch(List<String> batch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getFlushIntervalMs());
        while (batch.size() < config.getBatchSize()) {
            if (!running) {
                // shutting down: take whatever is already queued and leave
                queue.drainTo(batch, config.getBatchSize() - batch.size());
                return;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                return;
            String next = queue.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
            if (next != null)
                batch.add(next);
        }
    }

    private void send(List<String> batch) {
        String body;
        try {
            body = bulkPayload(batch);
        } catch (JsonProcessingException ex) {
            failed.addAndGet(batch.size());
            logThrottled("could not serialize Matomo payload", ex);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getUrl()))
                .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "GraphHopper-Matomo-Tracker")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                sent.addAndGet(batch.size());
            } else {
                failed.addAndGet(batch.size());
                logThrottled("Matomo returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()), null);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            failed.addAndGet(batch.size());
            logThrottled("could not reach Matomo at " + config.getUrl(), ex);
        }
    }

    String bulkPayload(List<String> batch) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (String hit : batch)
            requests.add(hit);
        if (!config.getTokenAuth().isEmpty())
            root.put("token_auth", config.getTokenAuth());
        return objectMapper.writeValueAsString(root);
    }

    private static String abbreviate(String body) {
        if (body == null)
            return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** Matomo being down must not flood the log, so errors are reported at most once per minute. */
    private void logThrottled(String message, Exception ex) {
        long now = System.nanoTime();
        if (lastErrorLogNanos != 0 && now - lastErrorLogNanos < ERROR_LOG_INTERVAL_NANOS)
            return;
        lastErrorLogNanos = now;
        if (ex == null)
            logger.warn("{} ({} hits failed so far, {} dropped)", message, failed.get(), dropped.get());
        else
            logger.warn("{} ({} hits failed so far, {} dropped): {}", message, failed.get(), dropped.get(), ex.toString());
    }

    long getSentCount() {
        return sent.get();
    }

    long getDroppedCount() {
        return dropped.get();
    }

    long getFailedCount() {
        return failed.get();
    }
}
