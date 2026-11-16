package eu.hansolo.mcp.client;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs the mcp-it-fixture server ({@code ask_contact}, {@code connect_account}): {@code -Dmcp.fixture.url=http://localhost:8080/mcp}. */
class ElicitationIT {

    private static URI endpoint() {
        String url = System.getProperty("mcp.fixture.url");
        assumeTrue(url != null, "mcp.fixture.url not set");
        return URI.create(url);
    }

    private static String text(ToolResult result) {
        return result.content().getJsonObject(0).getString("text");
    }

    @Test
    void aRefusedAnswerIsAskedAgainAndTheSecondTryCompletes() {
        List<String> messages = new ArrayList<>();
        McpClient client = McpClient.builder(endpoint()).elicitation(request -> {
            messages.add(request.message());
            int age = messages.size() == 1 ? 17 : 36;
            return Elicitation.Response.accept(Json.createObjectBuilder().add("name", "Ada").add("email", "ada@example.com").add("age", age).build());
        }).validateElicitation(false).build();   // this test is about the server refusing the first answer
        ToolResult result = client.callTool("ask_contact", null);
        assertTrue(result.complete());
        assertEquals("Hello Ada <ada@example.com>, age 36", text(result));
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("'age'"), messages.get(1));
    }

    @Test
    void declineEndsTheCall() {
        McpClient client = McpClient.builder(endpoint()).elicitation(request -> Elicitation.Response.decline()).build();
        assertEquals("declined", text(client.callTool("ask_contact", null)));
    }

    @Test
    void urlModeKeepsTheSignedStateAcrossTheRetry() {
        List<Elicitation.Request> asked  = new ArrayList<>();
        McpClient                 client = McpClient.builder(endpoint()).elicitation(request -> {
            asked.add(request);
            return Elicitation.Response.accept();
        }).build();
        ToolResult result = client.callTool("connect_account", null);
        assertEquals("connected after connect", text(result));
        assertEquals(Elicitation.Mode.URL, asked.get(0).mode());
        assertEquals("https://accounts.example.com/connect?ticket=abc", asked.get(0).url());
    }

    @Test
    void withoutAHandlerTheServerRefusesWithMissingCapability() {
        McpClient client = McpClient.builder(endpoint()).build();
        ToolResult result = client.callTool("ask_contact", null);
        assertTrue(result.isError());
        assertEquals("This client cannot ask the user", text(result));
    }
}
