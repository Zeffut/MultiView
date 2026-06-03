package fr.zeffut.multiview.telemetry;

import com.posthog.java.PostHog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** {@link TelemetrySink} backed by the (shaded) posthog-java SDK, EU host. */
public final class PostHogClient implements TelemetrySink {
    private static final Logger LOG = LoggerFactory.getLogger(PostHogClient.class);

    private final PostHog posthog;

    public PostHogClient(String apiKey, String host) {
        this.posthog = new PostHog.Builder(apiKey).host(host).build();
    }

    @Override
    public void capture(String distinctId, String event, Map<String, Object> properties) {
        try {
            posthog.capture(distinctId, event, properties);
        } catch (Throwable t) {
            LOG.debug("[MultiView] telemetry capture failed: {}", t.getMessage());
        }
    }

    @Override
    public void flush() {
        // posthog-java 1.2.0 exposes no public flush(); its QueueManager drains on a
        // background timer and on shutdown(). Nothing to do here.
    }

    @Override
    public void shutdown() {
        try { posthog.shutdown(); } catch (Throwable t) { LOG.debug("shutdown failed: {}", t.getMessage()); }
    }
}
