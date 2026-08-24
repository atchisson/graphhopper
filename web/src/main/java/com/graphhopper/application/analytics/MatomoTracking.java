package com.graphhopper.application.analytics;

import io.dropwizard.core.setup.Environment;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * Wires the server-side Matomo tracking into the Dropwizard environment.
 */
public final class MatomoTracking {

    private static final Logger logger = LoggerFactory.getLogger(MatomoTracking.class);

    private MatomoTracking() {
    }

    public static void register(MatomoConfig config, Environment environment) {
        if (!config.isEnabled()) {
            logger.debug("Matomo tracking is disabled");
            return;
        }
        if (config.getUrl().isEmpty())
            throw new IllegalArgumentException("matomo.enabled is true but matomo.url is missing. Set it to the "
                    + "tracking endpoint of your Matomo instance, e.g. https://analytics.example.com/matomo.php");
        if (config.getTokenAuth().isEmpty())
            throw new IllegalArgumentException("matomo.enabled is true but matomo.token_auth is missing. Without an "
                    + "auth token Matomo ignores the visitor IP and user agent we send, and every visitor would be "
                    + "counted as this server itself. Create a token in Matomo under Administration > Personal > "
                    + "Security > Auth tokens.");
        if (config.getApiSampleRate() < 0 || config.getApiSampleRate() > 1)
            throw new IllegalArgumentException("matomo.api_sample_rate must be between 0 and 1 but was " + config.getApiSampleRate());

        MatomoTracker tracker = new MatomoTracker(config);
        environment.lifecycle().manage(tracker);
        environment.servlets()
                .addFilter("matomo", new MatomoTrackingFilter(config, tracker))
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "*");
        logger.info("Tracking page views{} server-side to Matomo", config.isTrackApiRequests()
                ? " and API events for " + MatomoHitBuilder.supportedEndpoints() : "");
    }
}
