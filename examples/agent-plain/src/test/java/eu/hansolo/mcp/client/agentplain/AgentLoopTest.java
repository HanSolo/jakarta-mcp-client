package eu.hansolo.mcp.client.agentplain;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentLoopTest {
    private static final ToolBackend ADD_BACKEND = new ToolBackend() {
        @Override public JsonArray tools() {
            return Json.createArrayBuilder().add(Json.createObjectBuilder().add("name", "add").add("description", "Adds two numbers").add("input_schema", Json.createObjectBuilder().add("type", "object"))).build();
        }

        @Override public Outcome call(final String name, final JsonObject arguments) {
            return new Outcome("{\"sum\":" + (arguments.getJsonNumber("a").doubleValue() + arguments.getJsonNumber("b").doubleValue()) + "}", false);
        }
    };


    @Test
    void fakeModelAddsTwoAndThree() {
        AgentLoop.Answer answer = new AgentLoop(new FakeLlm(), ADD_BACKEND, 5).run("add 2 and 3");
        assertFalse(answer.truncated());
        assertEquals(2, answer.rounds());
        assertEquals(1, answer.calls().size());
        assertEquals("add", answer.calls().get(0).name());
        assertTrue(answer.text().contains("5"), answer.text());
    }

    @Test
    void aQuestionWithoutAToolCallIsAnsweredInOneRound() {
        AgentLoop.Answer answer = new AgentLoop(new FakeLlm(), ADD_BACKEND, 5).run("hello");
        assertEquals(1, answer.rounds());
        assertTrue(answer.calls().isEmpty());
    }

    @Test
    void theRoundLimitStopsAModelThatNeverAnswers() {
        Llm endless = (tools, messages) -> Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", "tool_use").add("id", "t").add("name", "add").add("input", Json.createObjectBuilder().add("a", 1).add("b", 1))).build();
        AgentLoop.Answer answer = new AgentLoop(endless, ADD_BACKEND, 3).run("loop");
        assertTrue(answer.truncated());
        assertEquals(3, answer.calls().size());
    }
}
