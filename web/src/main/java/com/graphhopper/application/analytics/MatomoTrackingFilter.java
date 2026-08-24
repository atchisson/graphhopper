package com.graphhopper.application.analytics;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reports requests to Matomo from the server, so that the statistics are unaffected by content blockers.
 * <p>
 * The map UI is reported as a page view, the routing endpoints as Matomo events. Everything else - static
 * assets, vector tiles, health checks - is ignored. All the work happens on the tracker's background thread,
 * this filter only reads what it needs off the request.
 */
public class MatomoTrackingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(MatomoTrackingFilter.class);

    private final MatomoConfig config;
    private final MatomoTracker tracker;
    private final MatomoHitBuilder hitBuilder;

    public MatomoTrackingFilter(MatomoConfig config, MatomoTracker tracker) {
        this.config = config;
        this.tracker = tracker;
        this.hitBuilder = new MatomoHitBuilder(config);
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse rsp = (HttpServletResponse) response;
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                Map<String, String> params = hitBuilder.build(toRequest(req, rsp.getStatus(),
                        (System.nanoTime() - startNanos) / 1_000_000L));
                if (params != null)
                    tracker.track(params);
            } catch (Exception ex) {
                // tracking must never affect the response
                logger.debug("could not report request to Matomo", ex);
            }
        }
    }

    private MatomoHitBuilder.Request toRequest(HttpServletRequest req, int status, long durationMs) {
        return new MatomoHitBuilder.Request(req.getMethod(), req.getRequestURI(), status, durationMs,
                firstValues(req), clientIp(req), req.getHeader("User-Agent"), req.getHeader("Accept-Language"),
                req.getHeader("Referer"));
    }

    /**
     * The query parameters, keeping only the first value of each. Note that the GraphHopper endpoints take their
     * POST payload as JSON, so this never touches the request body.
     */
    private static Map<String, String> firstValues(HttpServletRequest req) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
            if (e.getValue() != null && e.getValue().length > 0)
                result.put(e.getKey(), e.getValue()[0]);
        }
        return result;
    }

    private String clientIp(HttpServletRequest req) {
        if (config.isTrustForwardedFor()) {
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                // the left-most entry is the original client, the rest are the proxies it went through
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty())
                    return first;
            }
        }
        return req.getRemoteAddr();
    }
}
