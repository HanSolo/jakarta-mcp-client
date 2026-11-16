package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stream that goes quiet is cut after the idle time; keep-alive comments keep it open. */
class IdleGuardTest {

    private HttpServer   server;
    private URI          endpoint;
    private volatile int commentsBeforeResult;
    private volatile int quietMillisAfterComments;


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
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int    id   = Integer.parseInt(body.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
        if ("tools/list".equals(exchange.getRequestHeaders().getFirst("Mcp-Method"))) {
            byte[] json = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[],\"ttlMs\":60000}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < commentsBeforeResult; i++) {
                out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            }
            Thread.sleep(quietMillisAfterComments);
            out.write(("data: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"resultType\":\"complete\",\"content\":[],\"isError\":false}}\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | InterruptedException e) {
            // the client hung up
        }
    }

    @Test
    void aQuietStreamIsCut() {
        commentsBeforeResult     = 1;
        quietMillisAfterComments = 5_000;
        McpClient          client = McpClient.builder(endpoint).streamIdleTimeout(Duration.ofMillis(400)).build();
        long               start  = System.nanoTime();
        McpClientException e      = assertThrows(McpClientException.class, () -> client.callTool("slow", Json.createObjectBuilder().build()));
        assertTrue(e.getMessage().contains("No data from the server"), e.getMessage());
        assertTrue(System.nanoTime() - start < 3_000_000_000L, "cut long before the server answered");
    }

    @Test
    void keepAliveCommentsKeepAStreamOpen() {
        commentsBeforeResult     = 8;   // 800 ms of comments, twice the idle time
        quietMillisAfterComments = 0;
        McpClient client = McpClient.builder(endpoint).streamIdleTimeout(Duration.ofMillis(400)).build();
        assertFalse(client.callTool("slow", Json.createObjectBuilder().build()).isError());
    }
}
