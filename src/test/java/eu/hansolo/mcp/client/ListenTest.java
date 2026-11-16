package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Caching and the listen stream against an in-process server that fakes the list methods and lets the test push notifications. */
class ListenTest {

    private static final String CLOSE = "CLOSE";

    private final BlockingQueue<String> pushed        = new LinkedBlockingQueue<>();
    private final AtomicInteger         toolLists     = new AtomicInteger();
    private final AtomicInteger         discovers     = new AtomicInteger();
    private final AtomicInteger         listens       = new AtomicInteger();
    private final CountDownLatch        streamDropped = new CountDownLatch(1);
    private       HttpServer            server;
    private       URI                   endpoint;
    private volatile String             scope         = "public";
    private volatile boolean            refuseListen;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/mcp", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }


    private void handle(HttpExchange exchange) throws IOException {
        JsonObject request = Json.createReader(new StringReader(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))).readObject();
        int        id      = request.getInt("id");
        String     method  = request.getString("method");
        if (method.equals("subscriptions/listen")) {
            listen(exchange, id);
            return;
        }
        String result = switch (method) {
            case "tools/list" -> "{\"tools\":[{\"name\":\"tool-" + toolLists.incrementAndGet() + "\"}],\"ttlMs\":60000,\"cacheScope\":\"" + scope + "\"}";
            case "server/discover" -> "{\"supportedVersions\":[\"2026-07-28\"],\"calls\":" + discovers.incrementAndGet() + ",\"ttlMs\":60000}";
            default -> "{}";
        };
        send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    private void listen(HttpExchange exchange, int id) throws IOException {
        listens.incrementAndGet();
        if (refuseListen) {
            send(exchange, 400, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32021,\"message\":\"needs a capability\"}}");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            event(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/subscriptions/acknowledged\",\"params\":{\"notifications\":{\"toolsListChanged\":true}}}");
            while (true) {
                String next = pushed.poll(20, TimeUnit.MILLISECONDS);
                if (next == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else if (next.equals(CLOSE)) {
                    event(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"resultType\":\"complete\"}}");
                    break;
                } else {
                    event(out, next);
                }
            }
        } catch (IOException e) {
            streamDropped.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void event(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private McpClient.Builder client() {
        return McpClient.builder(endpoint).listenBackoff(Duration.ofMillis(30), Duration.ofMillis(100));
    }


    /** Counts what the listener hears. */
    private static final class Recorder implements McpListener {

        final CountDownLatch   subscribed    = new CountDownLatch(1);
        final CountDownLatch   subscribedTwice = new CountDownLatch(2);
        final CountDownLatch   toolsChanged  = new CountDownLatch(1);
        final CountDownLatch   disconnected  = new CountDownLatch(1);
        final CountDownLatch   task          = new CountDownLatch(1);
        final AtomicReference<TaskState> taskState = new AtomicReference<>();
        final AtomicReference<Throwable> cause     = new AtomicReference<>();
        final AtomicReference<Boolean>   retry     = new AtomicReference<>();

        @Override
        public void onSubscribed(JsonObject honored) {
            subscribed.countDown();
            subscribedTwice.countDown();
        }

        @Override
        public void onToolsListChanged() {
            toolsChanged.countDown();
        }

        @Override
        public void onTaskUpdated(TaskState state) {
            taskState.set(state);
            task.countDown();
        }

        @Override
        public void onDisconnected(Throwable why, boolean willRetry) {
            cause.set(why);
            retry.set(willRetry);
            disconnected.countDown();
        }
    }


    @Test
    void theToolListIsCachedAndLoadedAgainAfterAListChangedNotification() throws Exception {
        McpClient client = client().build();
        assertEquals("tool-1", client.tools().get(0).getString("name"));
        assertEquals("tool-1", client.tools().get(0).getString("name"));
        assertEquals(1, toolLists.get());
        Recorder recorder = new Recorder();
        try (Subscription subscription = client.listen(ListenFilter.create().toolsListChanged(), recorder)) {
            assertTrue(recorder.subscribed.await(5, TimeUnit.SECONDS));
            pushed.add("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}");
            assertTrue(recorder.toolsChanged.await(5, TimeUnit.SECONDS));
            assertEquals("tool-2", client.tools().get(0).getString("name"));
            assertEquals(2, toolLists.get());
        }
    }

    @Test
    void discoveryIsCachedUntilTheCachesAreCleared() {
        McpClient client = client().build();
        assertEquals(1, client.discover().getInt("calls"));
        assertEquals(1, client.discover().getInt("calls"));
        client.clearCaches();
        assertEquals(2, client.discover().getInt("calls"));
    }

    @Test
    void aPrivateListIsLoadedAgainForAnotherToken() {
        scope = "private";
        AtomicReference<String> token  = new AtomicReference<>("alice");
        McpClient               client = client().bearerToken(token::get).build();
        client.tools();
        client.tools();
        assertEquals(1, toolLists.get());
        token.set("bob");
        client.tools();
        assertEquals(2, toolLists.get());
    }

    @Test
    void aStreamTheServerEndsIsOpenedAgainAndTheCachesAreDropped() throws Exception {
        McpClient client   = client().build();
        Recorder  recorder = new Recorder();
        client.tools();
        try (Subscription subscription = client.listen(ListenFilter.create().toolsListChanged(), recorder)) {
            assertTrue(recorder.subscribed.await(5, TimeUnit.SECONDS));
            pushed.add(CLOSE);
            assertTrue(recorder.disconnected.await(5, TimeUnit.SECONDS));
            assertNull(recorder.cause.get());
            assertTrue(recorder.retry.get());
            assertTrue(recorder.subscribedTwice.await(5, TimeUnit.SECONDS), "the stream was not opened again");
            assertEquals(2, listens.get());
            client.tools();
            assertEquals(2, toolLists.get());
        }
    }

    @Test
    void aRefusedSubscriptionIsNotRetried() throws Exception {
        refuseListen = true;
        Recorder recorder = new Recorder();
        try (Subscription subscription = client().build().listen(ListenFilter.create().toolsListChanged(), recorder)) {
            assertTrue(recorder.disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(recorder.retry.get());
            assertEquals(-32021, ((McpClientException) recorder.cause.get()).code());
            Thread.sleep(300);
            assertEquals(1, listens.get());
        }
    }

    @Test
    void taskNotificationsBecomeTaskStates() throws Exception {
        Recorder recorder = new Recorder();
        try (Subscription subscription = client().tasks().build().listen(ListenFilter.create().tasks("T1"), recorder)) {
            assertTrue(recorder.subscribed.await(5, TimeUnit.SECONDS));
            pushed.add("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tasks\",\"params\":{\"taskId\":\"T1\",\"status\":\"completed\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}}");
            assertTrue(recorder.task.await(5, TimeUnit.SECONDS));
            assertTrue(recorder.taskState.get().completed());
            assertEquals("ok", recorder.taskState.get().toolResult().content().getJsonObject(0).getString("text"));
        }
    }

    @Test
    void closingTheSubscriptionDropsTheConnection() throws Exception {
        Recorder     recorder     = new Recorder();
        Subscription subscription = client().build().listen(ListenFilter.create().toolsListChanged(), recorder);
        assertTrue(recorder.subscribed.await(5, TimeUnit.SECONDS));
        subscription.close();
        assertTrue(streamDropped.await(5, TimeUnit.SECONDS), "the server did not notice the closed stream");
        Thread.sleep(300);
        assertEquals(1, listens.get());
    }
}
