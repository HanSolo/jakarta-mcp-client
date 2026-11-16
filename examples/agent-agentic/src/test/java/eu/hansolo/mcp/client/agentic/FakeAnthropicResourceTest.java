package eu.hansolo.mcp.client.agentic;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FakeAnthropicResourceTest {

    @Test
    void theReplyHasTheShapeOfTheMessagesApi() {
        String prompt = "tools: [{\"name\":\"add\"}]\nQuestion: add 2 and 3";
        JsonObject request = Json.createObjectBuilder().add("model", "x").add("messages", Json.createArrayBuilder().add(Json.createObjectBuilder().add("role", "user").add("content", prompt))).build();
        JsonObject reply   = new FakeAnthropicResource().messages(request);
        assertEquals("text", reply.getJsonArray("content").getJsonObject(0).getString("type"));
        assertTrue(reply.getJsonArray("content").getJsonObject(0).getString("text").contains("\"tool\":\"add\""));
    }
}
