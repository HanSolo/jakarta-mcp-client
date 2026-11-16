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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tasks methods against an in-process server whose task goes working, input_required, working, completed (or failed). */
class TasksTest {

    private static final String PUBLISH = "{\"publish\":{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\",\"message\":\"Publish?\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"publish\":{\"type\":\"boolean\"}}}}}}";

    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final List<String>     names    = new CopyOnWriteArrayList<>();
    private final AtomicInteger    gets     = new AtomicInteger();
    private       HttpServer       server;
    private       URI              endpoint;
    private       boolean          answered;
    private       boolean          failAtEnd;


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


    private static String task(String status, String extra) {
        return "{\"resultType\":\"" + (status.equals("created") ? "task" : "complete") + "\",\"taskId\":\"T1\",\"status\":\"" + (status.equals("created") ? "working" : status) + "\",\"lastUpdatedAt\":\"2026-11-16T10:00:0" + extra.length() % 10 + "Z\",\"pollIntervalMs\":10" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonObject request = Json.createReader(new StringReader(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))).readObject();
        String     method  = request.getString("method");
        int        id      = request.getInt("id");
        String     result;
        switch (method) {
            case "tools/list" -> result = "{\"tools\":[],\"ttlMs\":60000}";
            case "tools/call" -> {
                requests.add(request);
                result = task("created", "");
            }
            case "tasks/get" -> {
                requests.add(request);
                names.add(exchange.getRequestHeaders().getFirst("Mcp-Name"));
                int n = gets.incrementAndGet();
                if (n == 1) {
                    result = task("working", "");
                } else if (!answered) {
                    result = task("input_required", "\"inputRequests\":" + PUBLISH);
                } else if (n < 5) {
                    result = task("working", "\"statusMessage\":\"writing\"");
                } else if (failAtEnd) {
                    result = task("failed", "\"error\":{\"code\":-32603,\"message\":\"disk full\"}");
                } else {
                    result = task("completed", "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"published\"}]}");
                }
            }
            case "tasks/update" -> {
                requests.add(request);
                answered = true;
                result = "{\"resultType\":\"complete\"}";
            }
            case "tasks/cancel" -> {
                requests.add(request);
                result = "{\"resultType\":\"complete\"}";
            }
            default -> result = "{}";
        }
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Elicitation.Handler publisher() {
        return request -> Elicitation.Response.accept(Json.createObjectBuilder().add("publish", true).build());
    }


    @Test
    void theExtensionIsDeclaredAndTheCallAnswersWithATask() {
        McpClient  client = McpClient.builder(endpoint).tasks().build();
        ToolResult result = client.callTool("build_report", null);
        assertTrue(result.isTask());
        assertFalse(result.complete());
        assertEquals("T1", result.task().taskId());
        assertTrue(result.task().working());
        JsonObject capabilities = requests.get(0).getJsonObject("params").getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/clientCapabilities");
        assertTrue(capabilities.getJsonObject("extensions").containsKey("io.modelcontextprotocol/tasks"), capabilities.toString());
    }

    @Test
    void awaitAnswersTheOpenRequestOnceAndReturnsTheCompletedTask() {
        McpClient client = McpClient.builder(endpoint).tasks().elicitation(publisher()).build();
        TaskState done   = client.awaitTask("T1", Duration.ofSeconds(10));
        assertTrue(done.completed());
        assertEquals("published", done.toolResult().content().getJsonObject(0).getString("text"));
        List<JsonObject> updates = requests.stream().filter(r -> r.getString("method").equals("tasks/update")).toList();
        assertEquals(1, updates.size());
        JsonObject params = updates.get(0).getJsonObject("params");
        assertEquals("T1", params.getString("taskId"));
        assertEquals("accept", params.getJsonObject("inputResponses").getJsonObject("publish").getString("action"));
        assertTrue(params.getJsonObject("inputResponses").getJsonObject("publish").getJsonObject("content").getBoolean("publish"));
        assertTrue(names.stream().allMatch("T1"::equals), names.toString());
    }

    @Test
    void callToolAndAwaitReturnsTheResultOfTheTask() {
        McpClient  client = McpClient.builder(endpoint).tasks().elicitation(publisher()).build();
        ToolResult result = client.callToolAndAwait("build_report", null, null, Duration.ofSeconds(10));
        assertTrue(result.complete());
        assertEquals("published", result.content().getJsonObject(0).getString("text"));
    }

    @Test
    void aFailedTaskThrowsItsError() {
        failAtEnd = true;
        McpClient client = McpClient.builder(endpoint).tasks().elicitation(publisher()).build();
        McpClientException e = assertThrows(McpClientException.class, () -> client.callToolAndAwait("build_report", null, null, Duration.ofSeconds(10)));
        assertEquals(-32603, e.code());
        assertEquals("disk full", e.getMessage());
    }

    @Test
    void withoutAHandlerTheWaitEndsWithATimeout() {
        McpClient client = McpClient.builder(endpoint).tasks().build();
        McpClientException e = assertThrows(McpClientException.class, () -> client.awaitTask("T1", Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertNull(e.getCause());
        assertTrue(requests.stream().noneMatch(r -> r.getString("method").equals("tasks/update")));
    }

    @Test
    void cancelSendsTheTaskIdAsNameAndParameter() {
        McpClient.builder(endpoint).tasks().build().cancelTask("T1");
        JsonObject cancel = requests.get(0);
        assertEquals("tasks/cancel", cancel.getString("method"));
        assertEquals("T1", cancel.getJsonObject("params").getString("taskId"));
    }
}
