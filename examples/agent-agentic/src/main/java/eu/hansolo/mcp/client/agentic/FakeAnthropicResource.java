package eu.hansolo.mcp.client.agentic;

import jakarta.enterprise.context.RequestScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;


/**
 * A stand-in for the Anthropic Messages API, answering with the scripted {@link FakeModel}. Nothing uses it unless the runtime is pointed at it:
 * {@code PAYARA_AGENTIC_LLM_ANTHROPIC_BASE_URL=http://localhost:8080/api/fake-anthropic}. That way the whole path (runtime, its Anthropic backend, the agent) runs without a key or a network.
 */
@Path("/fake-anthropic/v1/messages")
@RequestScoped
public class FakeAnthropicResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JsonObject messages(final JsonObject request) {
        JsonArray messages = request.getJsonArray("messages");
        String    last     = messages.getJsonObject(messages.size() - 1).getString("content", "");
        return Json.createObjectBuilder().add("id", "msg_fake").add("type", "message").add("role", "assistant").add("stop_reason", "end_turn")
                   .add("content", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", "text").add("text", FakeModel.reply(last)))).build();
    }
}
