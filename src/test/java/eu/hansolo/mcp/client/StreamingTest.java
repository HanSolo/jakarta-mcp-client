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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the client against a tiny in-process server, so streaming and cancellation are tested without Docker. */
class StreamingTest {

    private HttpServer     server;
    private URI            endpoint;
    private CountDownLatch disconnected = new CountDownLatch(1);


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        String     method  = request.getString("method");
        int        id      = request.getInt("id");
        if (method.equals("tools/list")) {
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[],\"ttlMs\":60000}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        String token = request.getJsonObject("params").getJsonObject("_meta").getString("progressToken", null);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            event(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"someone-else\",\"progress\":99}}");
            event(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"" + token + "\",\"progress\":1,\"total\":2,\"message\":\"half\"}}");
            if (request.getJsonObject("params").getString("name").equals("hang")) {
                while (true) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(20);
                }
            }
            event(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"" + token + "\",\"progress\":2,\"total\":2}}");
            event(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}");
        } catch (IOException e) {
            disconnected.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void event(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }


    @Test
    void progressEventsReachTheListenerInOrderAndForeignTokensAreIgnored() {
        List<Progress> seen   = new CopyOnWriteArrayList<>();
        ToolResult     result = McpClient.builder(endpoint).build().callTool("work", null, seen::add);
        assertEquals(List.of(new Progress(1, 2.0, "half"), new Progress(2, 2.0, null)), seen);
        assertTrue(result.complete());
        assertEquals("done", result.content().getJsonObject(0).getString("text"));
    }

    @Test
    void cancelDropsTheConnectionAndCompletesTheResultAsCancelled() throws Exception {
        CountDownLatch first  = new CountDownLatch(1);
        ToolCallHandle handle = McpClient.builder(endpoint).build().startToolCall("hang", null, progress -> first.countDown());
        assertTrue(first.await(5, TimeUnit.SECONDS), "no progress event arrived");
        handle.cancel();
        assertThrows(CancellationException.class, () -> handle.result().get(5, TimeUnit.SECONDS));
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the server did not notice the dropped connection");
    }

    @Test
    void aMalformedEndpointIsRejectedWhenBuilding() {
        assertThrows(IllegalArgumentException.class, () -> McpClient.builder(URI.create("http://localhost:PORT/mcp")).build());
        assertThrows(IllegalArgumentException.class, () -> McpClient.builder(URI.create("/mcp")).build());
    }
}
