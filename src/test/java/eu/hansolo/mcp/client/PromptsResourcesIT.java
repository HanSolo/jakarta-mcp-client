package eu.hansolo.mcp.client;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** getPrompt and readResource against the tickets server: {@code -Dmcp.auth.url=http://localhost:8080/mcp} (Keycloak and tickets stack). */
class PromptsResourcesIT {

    private static McpClient client;

    @BeforeAll
    static void gate() {
        String url = System.getProperty("mcp.auth.url");
        assumeTrue(url != null, "set -Dmcp.auth.url to run");
        client = McpClient.builder(URI.create(url)).oauthClientCredentials("mcp-test", "mcp-test-secret").build();
    }

    @Test
    void theTriagePromptIsRendered() {
        JsonObject prompt = client.getPrompt("triage", Map.of("ticketId", "1"));
        String     text   = prompt.getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text");
        assertTrue(text.contains("Ticket 1 "), text);
    }

    @Test
    void aTicketIsReadByUri() {
        JsonObject result = client.readResource("ticket://1");
        assertTrue(result.getJsonArray("contents").getJsonObject(0).getString("text").contains("\"id\":1"));
    }

    @Test
    void thePromptArgumentIsCompleted() {
        Completion completion = client.completePrompt("triage", "ticketId", "11", null);
        assertTrue(completion.values().contains("11") && completion.values().contains("110"), completion.values().toString());
    }
}
