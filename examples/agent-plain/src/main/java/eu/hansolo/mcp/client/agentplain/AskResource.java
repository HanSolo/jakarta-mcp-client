package eu.hansolo.mcp.client.agentplain;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.cdi.McpConnection;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;


/** POST /api/ask with {@code {"question": "..."}}: answers with {@code answer}, {@code rounds}, {@code truncated} and the {@code toolCalls} that were made. */
@Path("/ask")
@RequestScoped
public class AskResource {
    @Inject
    @McpConnection("example")
    private McpClient client;

    @Inject
    private LlmProvider llms;


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ask(final JsonObject body) {
        String question = body.getString("question", "").trim();
        if (question.isEmpty()) { return error(400, "The body needs a non-empty \"question\"."); }
        try {
            AgentLoop.Answer answer = new AgentLoop(llms.llm(), new McpToolBackend(client), llms.maxRounds()).run(question);
            return Response.ok(answer.toJson()).build();
        } catch (IllegalStateException e) {
            return error(502, e.getMessage());
        }
    }

    private static Response error(final int status, final String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Json.createObjectBuilder().add("error", message).build()).build();
    }
}
