package eu.hansolo.mcp.client.agentic;

import jakarta.ai.agent.LLMException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** An agent is a plain Java class: its phases are called directly, with a scripted model and no server. */
class McpAgentTest {
    private final McpAgent agent    = new McpAgent();
    private final Answers  answers  = new Answers();
    private final Question question = new Question("q1", "add 2 and 3");


    McpAgentTest() {
        agent.answers   = answers;
        agent.backend   = new StubBackend();
        agent.maxRounds = 5;
    }


    @Test
    void theActionRunsTheToolLoopAndTheOutcomePublishesTheAnswer() {
        AgentLoop.Answer answer = agent.ask(question, new ScriptedModel());
        assertEquals(1, answer.calls().size());
        assertTrue(answer.text().contains("5"), answer.text());
        agent.publish(question, answer);
        assertEquals(answer, answers.take("q1"));
        assertNull(answers.take("q1"));
    }

    @Test
    void aFailedModelIsStoredByTheHandlerAndTheOutcomeAddsNothing() {
        agent.llmFailed(new LLMException("model is down"), question);
        agent.publish(question, null);
        AgentLoop.Answer answer = answers.take("q1");
        assertNotNull(answer);
        assertEquals("model is down", answer.error());
    }
}
