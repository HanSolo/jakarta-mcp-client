package eu.hansolo.mcp.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs the mcp-it-fixture server, whose {@code /admin/tools/<name>} adds and removes a tool at runtime: {@code -Dmcp.fixture.url=http://localhost:8080/mcp}. */
class ListenIT {

    private final HttpClient http = HttpClient.newHttpClient();


    private int admin(URI endpoint, String method, String name) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/admin/tools/" + name)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static boolean listed(McpClient client, String name) {
        return client.tools().stream().anyMatch(tool -> name.equals(tool.getString("name", "")));
    }

    @Test
    void aToolAddedWhileListeningShowsUpInTheCachedList() throws Exception {
        String url = System.getProperty("mcp.fixture.url");
        assumeTrue(url != null, "mcp.fixture.url not set");
        URI       endpoint = URI.create(url);
        McpClient client   = McpClient.builder(endpoint).build();
        String    name     = "dyn_client_added";
        admin(endpoint, "DELETE", name);
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch changed    = new CountDownLatch(1);
        try (Subscription subscription = client.listen(ListenFilter.create().toolsListChanged(), new McpListener() {

            @Override
            public void onSubscribed(jakarta.json.JsonObject honored) {
                subscribed.countDown();
            }

            @Override
            public void onToolsListChanged() {
                changed.countDown();
            }
        })) {
            assertTrue(subscribed.await(20, TimeUnit.SECONDS), "no acknowledgement");
            assertFalse(listed(client, name));
            assertEquals(204, admin(endpoint, "POST", name));
            assertTrue(changed.await(20, TimeUnit.SECONDS), "no list_changed notification");
            assertTrue(listed(client, name), "the cached list was not refreshed");
        } finally {
            admin(endpoint, "DELETE", name);
        }
    }
}
