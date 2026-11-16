package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** prompts/get and resources/read: the right method, the right Mcp-Name, the arguments, and the raw result back. */
class PromptsAndResourcesTest {

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private       HttpServer   server;
    private       URI          endpoint;


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
        seen.add(exchange.getRequestHeaders().getFirst("Mcp-Method") + "|" + exchange.getRequestHeaders().getFirst("Mcp-Name") + "|" + request.getJsonObject("params").toString().replaceAll("\"_meta\":\\{.*\\}", "").replace(",}", "}"));
        String method = request.getString("method");
        String result = method.equals("completion/complete") ? "{\"completion\":{\"values\":[\"11\",\"12\"],\"total\":40,\"hasMore\":true}}" : method.equals("prompts/get") ? "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hello\"}}]}" : "{\"contents\":[{\"uri\":\"ticket://1\",\"text\":\"one\"}]}";
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.getInt("id") + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void getPromptSendsTheNameAndStringArguments() {
        JsonObject result = McpClient.builder(endpoint).build().getPrompt("triage", Map.of("ticketId", "1"));
        assertEquals("hello", result.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text"));
        assertTrue(seen.get(0).startsWith("prompts/get|triage|"), seen.get(0));
        assertTrue(seen.get(0).contains("\"ticketId\":\"1\""), seen.get(0));
    }

    @Test
    void readResourceUsesTheUriAsName() {
        JsonObject result = McpClient.builder(endpoint).build().readResource("ticket://1");
        assertEquals("one", result.getJsonArray("contents").getJsonObject(0).getString("text"));
        assertTrue(seen.get(0).startsWith("resources/read|ticket://1|"), seen.get(0));
    }

    @Test
    void completionSendsTheReferenceTheArgumentAndTheContext() {
        Completion completion = McpClient.builder(endpoint).build().completePrompt("triage", "ticketId", "1", Map.of("team", "a"));
        assertEquals(java.util.List.of("11", "12"), completion.values());
        assertEquals(40, completion.total());
        assertTrue(completion.hasMore());
        assertTrue(seen.get(0).contains("\"type\":\"ref/prompt\""), seen.get(0));
        assertTrue(seen.get(0).contains("\"arguments\":{\"team\":\"a\"}"), seen.get(0));
        Completion resource = McpClient.builder(endpoint).build().completeResource("ticket://{id}", "id", "1", null);
        assertTrue(seen.get(1).contains("\"type\":\"ref/resource\""), seen.get(1));
        assertEquals(2, resource.values().size());
    }
}
