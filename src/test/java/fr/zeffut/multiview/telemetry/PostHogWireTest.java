package fr.zeffut.multiview.telemetry;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK wire test. Dummy project token and loopback only: never production ingestion. */
class PostHogWireTest {
    @Test
    void queuedCaptureDrainsOnShutdownWithoutIdentify() throws Exception {
        var requests = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/batch", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2);
            try (var out = exchange.getResponseBody()) { out.write("{}".getBytes(StandardCharsets.UTF_8)); }
            exchange.close();
        });
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        var client = new PostHogClient("phc_local_test_only", host);
        try {
            client.capture("anonymous-test-id", "sdk_compatibility_test", Map.of("app", "fixture", "value", 7));
            client.shutdown();
            String body = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(body, "SDK must drain the queue to the loopback receiver");
            var payload = JsonParser.parseString(body).getAsJsonObject();
            assertEquals("phc_local_test_only", payload.get("api_key").getAsString());
            var batch = payload.getAsJsonArray("batch");
            assertEquals(1, batch.size(), "No automatic identify or additional events");
            var event = batch.get(0).getAsJsonObject();
            assertEquals("sdk_compatibility_test", event.get("event").getAsString());
            assertEquals("anonymous-test-id", event.get("distinct_id").getAsString());
            assertTrue(event.has("uuid"));
            assertTrue(event.has("timestamp"));
            var props = event.getAsJsonObject("properties");
            assertEquals("fixture", props.get("app").getAsString());
            assertEquals(7, props.get("value").getAsInt());
            assertFalse(props.has("$set"));
            assertFalse(props.has("$set_once"));
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS), "No additional batches");
            assertDoesNotThrow(client::shutdown);
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }
}
