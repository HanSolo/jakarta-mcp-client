package eu.hansolo.mcp.client.agentic;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;


/**
 * The tool-calling loop on top of a model that only takes and returns text, which is all the Jakarta Agentic AI 1.0 facade offers. The model is asked to reply with one JSON
 * object: {@code {"tool": name, "arguments": {...}}} to call a tool, {@code {"answer": text}} to finish. The tool's result goes back as the next message. The conversation
 * is kept by whoever is behind the {@code model} function (the runtime keeps it per workflow), so each call sends only the new message.
 */
public final class AgentLoop {
    static final String RESULT_PREFIX = "Tool result for ";
    static final String CONTINUE      = "Continue: call another tool or give the final answer, as one JSON object.";
    private static final String PROTOCOL = """
            You can use tools. The available tools, as JSON with their input schemas:
            %s

            Reply with one JSON object and nothing else, no markdown. To call a tool: {"tool": "<name>", "arguments": {<arguments>}}. To give the final answer: {"answer": "<text>"}.
            After a tool call the next message holds the tool's result. Call a tool only when it helps.

            Question: %s
            """;
    private final Function<String, String> model;
    private final ToolBackend              backend;
    private final int                      maxRounds;


    public AgentLoop(final Function<String, String> model, final ToolBackend backend, final int maxRounds) {
        this.model     = model;
        this.backend   = backend;
        this.maxRounds = maxRounds;
    }


    public Answer run(final String question) {
        List<Call> calls = new ArrayList<>();
        String     reply = model.apply(PROTOCOL.formatted(backend.tools(), question));
        for (int round = 1; round <= maxRounds; round++) {
            Step step = parse(reply);
            if (step.tool() == null) { return new Answer(step.answer(), round, false, calls, null); }
            ToolBackend.Outcome outcome = backend.call(step.tool(), step.arguments());
            calls.add(new Call(step.tool(), step.arguments(), outcome.text(), outcome.isError()));
            if (round == maxRounds) { break; }
            reply = model.apply(RESULT_PREFIX + step.tool() + (outcome.isError() ? " (the tool reported an error)" : "") + ":\n" + outcome.text() + "\n" + CONTINUE);
        }
        return new Answer("Stopped after " + maxRounds + " rounds without a final answer.", maxRounds, true, calls, null);
    }

    /** A reply that is not a tool call or an answer in the agreed shape is taken as the answer itself: models often just answer in prose. */
    private static Step parse(final String reply) {
        int start = reply.indexOf('{');
        int end   = reply.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try (JsonReader reader = Json.createReader(new StringReader(reply.substring(start, end + 1)))) {
                JsonObject object = reader.readObject();
                if (object.get("tool") instanceof JsonString tool) { return new Step(tool.getString(), object.get("arguments") instanceof JsonObject arguments ? arguments : Json.createObjectBuilder().build(), null); }
                if (object.get("answer") instanceof JsonString answer) { return new Step(null, null, answer.getString()); }
            } catch (JsonException e) {
                // not JSON after all: fall through to the plain reply
            }
        }
        return new Step(null, null, reply.strip());
    }


    private record Step(String tool, JsonObject arguments, String answer) {}


    /** One tool call the model asked for. */
    public record Call(String name, JsonObject arguments, String result, boolean isError) {

        public JsonObject toJson() {
            return Json.createObjectBuilder().add("name", name).add("arguments", arguments).add("result", result).add("isError", isError).build();
        }
    }


    /** The final answer, the number of model replies it took, whether the round limit cut it short, every tool call in order, and an error text if the workflow failed. */
    public record Answer(String text, int rounds, boolean truncated, List<Call> calls, String error) {

        public static Answer failed(final String error) {
            return new Answer("", 0, false, List.of(), error);
        }

        public JsonObject toJson() {
            JsonArrayBuilder  toolCalls = Json.createArrayBuilder();
            for (Call call : calls) { toolCalls.add(call.toJson()); }
            JsonObjectBuilder json = Json.createObjectBuilder().add("answer", text).add("rounds", rounds).add("truncated", truncated).add("toolCalls", toolCalls);
            if (error != null) { json.add("error", error); }
            return json.build();
        }
    }
}
