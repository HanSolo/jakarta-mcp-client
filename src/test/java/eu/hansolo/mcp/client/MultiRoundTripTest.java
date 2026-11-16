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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The multi-round-trip loop against an in-process server that asks for a confirmation and remembers every request it got. */
class MultiRoundTripTest {

    private static final String ASK = "{\"resultType\":\"input_required\",\"inputRequests\":{\"confirm\":{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\",\"message\":\"Close ticket 7?\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}}},\"requestState\":\"STATE-1\"}";

    private final List<JsonObject> calls = new CopyOnWriteArrayList<>();
    private final List<String>     names = new CopyOnWriteArrayList<>();
    private       HttpServer       server;
    private       URI              endpoint;
    private       boolean          alwaysAsk;


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
        int        id      = request.getInt("id");
        String     result;
        if (request.getString("method").equals("tools/list")) {
            result = "{\"tools\":[],\"ttlMs\":60000}";
        } else {
            calls.add(request.getJsonObject("params"));
            names.add(exchange.getRequestHeaders().getFirst("Mcp-Name"));
            JsonObject answers = request.getJsonObject("params").getJsonObject("inputResponses");
            if (alwaysAsk || answers == null) {
                result = ASK;
            } else {
                String action = answers.getJsonObject("confirm").getString("action");
                result = action.equals("accept") ? "{\"content\":[{\"type\":\"text\",\"text\":\"closed\"}]}" : "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"not closed\"}]}";
            }
        }
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }


    @Test
    void theCallIsRepeatedWithTheAnswerAndTheUnchangedState() {
        List<Elicitation.Request> asked  = new ArrayList<>();
        McpClient                 client = McpClient.builder(endpoint).elicitation(request -> {
            asked.add(request);
            return Elicitation.Response.accept();
        }).build();
        ToolResult result = client.callTool("close_ticket", Json.createObjectBuilder().add("id", 7).build());
        assertTrue(result.complete());
        assertEquals("closed", result.content().getJsonObject(0).getString("text"));
        assertEquals(1, asked.size());
        assertEquals("confirm", asked.get(0).key());
        assertEquals(Elicitation.Mode.FORM, asked.get(0).mode());
        assertEquals("Close ticket 7?", asked.get(0).message());
        assertEquals(2, calls.size());
        JsonObject second = calls.get(1);
        assertEquals("STATE-1", second.getString("requestState"));
        assertEquals("accept", second.getJsonObject("inputResponses").getJsonObject("confirm").getString("action"));
        assertEquals(calls.get(0).getString("name"), second.getString("name"));
        assertEquals(calls.get(0).getJsonObject("arguments"), second.getJsonObject("arguments"));
        assertEquals(List.of("close_ticket", "close_ticket"), names);
    }

    @Test
    void aDeclineIsSentWithoutContent() {
        McpClient  client = McpClient.builder(endpoint).elicitation(request -> Elicitation.Response.decline()).build();
        ToolResult result = client.callTool("close_ticket", null);
        assertTrue(result.isError());
        JsonObject answer = calls.get(1).getJsonObject("inputResponses").getJsonObject("confirm");
        assertEquals("decline", answer.getString("action"));
        assertFalse(answer.containsKey("content"));
    }

    @Test
    void aNullAnswerCountsAsCancel() {
        McpClient client = McpClient.builder(endpoint).elicitation(request -> null).build();
        client.callTool("close_ticket", null);
        assertEquals("cancel", calls.get(1).getJsonObject("inputResponses").getJsonObject("confirm").getString("action"));
    }

    @Test
    void withoutAHandlerTheInputRequiredResultIsReturnedAsItIs() {
        ToolResult result = McpClient.builder(endpoint).build().callTool("close_ticket", null);
        assertFalse(result.complete());
        assertEquals("input_required", result.resultType());
        assertEquals("STATE-1", result.requestState());
        assertTrue(result.inputRequests().containsKey("confirm"));
        assertEquals(1, calls.size());
    }

    @Test
    void aServerThatKeepsAskingIsStopped() {
        alwaysAsk = true;
        McpClient client = McpClient.builder(endpoint).elicitation(request -> Elicitation.Response.accept()).maxInputRounds(3).build();
        McpClientException e = assertThrows(McpClientException.class, () -> client.callTool("close_ticket", null));
        assertTrue(e.getMessage().contains("3 rounds"), e.getMessage());
        assertEquals(4, calls.size());
        assertNull(e.getCause());
    }

    @Test
    void aModeThatWasNotDeclaredIsNotAnswered() {
        McpClient client = McpClient.builder(endpoint).elicitation(request -> Elicitation.Response.accept(), Elicitation.Mode.URL).build();
        McpClientException e = assertThrows(McpClientException.class, () -> client.callTool("close_ticket", null));
        assertTrue(e.getMessage().contains("did not declare"), e.getMessage());
    }
}
