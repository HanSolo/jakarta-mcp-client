package eu.hansolo.mcp.client.agentic;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;


/**
 * POST /api/ask with {@code {"question": "..."}}: fires the agent's trigger and answers with {@code answer}, {@code rounds}, {@code truncated} and the {@code toolCalls}. The
 * runtime observes the trigger event synchronously, so the workflow has ended when {@code fire} returns, and this request waits for it.
 */
@Path("/ask")
@RequestScoped
public class AskResource {
    @Inject
    Event<Question> questions;

    @Inject
    Answers answers;


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ask(final JsonObject body) {
        String text = body.getString("question", "").trim();
        if (text.isEmpty()) { return error(400, "The body needs a non-empty \"question\"."); }
        String id = UUID.randomUUID().toString();
        try {
            questions.fire(new Question(id, text));
        } catch (RuntimeException e) {
            return error(502, "The workflow failed: " + e.getMessage());
        }
        AgentLoop.Answer answer = answers.take(id);
        if (answer == null) { return error(500, "The workflow ended without an answer."); }
        return Response.status(answer.error() == null ? 200 : 502).type(MediaType.APPLICATION_JSON).entity(answer.toJson()).build();
    }

    private static Response error(final int status, final String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Json.createObjectBuilder().add("error", message).build()).build();
    }
}
