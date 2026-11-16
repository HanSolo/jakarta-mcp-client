package eu.hansolo.mcp.client.agentic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentLoopTest {

    @Test
    void scriptedModelAddsTwoAndThree() {
        AgentLoop.Answer answer = new AgentLoop(FakeModel::reply, new StubBackend(), 5).run("add 2 and 3");
        assertFalse(answer.truncated());
        assertEquals(2, answer.rounds());
        assertEquals(1, answer.calls().size());
        assertEquals("add", answer.calls().get(0).name());
        assertTrue(answer.text().contains("5"), answer.text());
    }

    @Test
    void aReplyInProseIsTheAnswer() {
        AgentLoop.Answer answer = new AgentLoop(prompt -> "Four.", new StubBackend(), 5).run("two plus two");
        assertEquals("Four.", answer.text());
        assertEquals(1, answer.rounds());
        assertTrue(answer.calls().isEmpty());
    }

    @Test
    void jsonInsideMarkdownFencesIsFound() {
        AgentLoop.Answer answer = new AgentLoop(prompt -> "```json\n{\"answer\": \"done\"}\n```", new StubBackend(), 5).run("anything");
        assertEquals("done", answer.text());
    }

    @Test
    void theRoundLimitStopsAModelThatNeverAnswers() {
        AgentLoop.Answer answer = new AgentLoop(prompt -> "{\"tool\":\"add\",\"arguments\":{\"a\":1,\"b\":1}}", new StubBackend(), 3).run("loop");
        assertTrue(answer.truncated());
        assertEquals(3, answer.calls().size());
    }

    @Test
    void theToolResultIsSentBackToTheModel() {
        StringBuilder seen = new StringBuilder();
        new AgentLoop(prompt -> { seen.append(prompt).append('\n'); return seen.toString().contains(AgentLoop.RESULT_PREFIX) ? "{\"answer\":\"ok\"}" : "{\"tool\":\"add\",\"arguments\":{\"a\":1,\"b\":2}}"; }, new StubBackend(), 5).run("x");
        assertTrue(seen.toString().contains(AgentLoop.RESULT_PREFIX + "add:\n{\"sum\":3.0}"), seen.toString());
    }
}
