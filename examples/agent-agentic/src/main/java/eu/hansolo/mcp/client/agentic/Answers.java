package eu.hansolo.mcp.client.agentic;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/** Where the agent's {@code @Outcome} leaves the answer for the request that fired the trigger: an outcome method returns nothing. */
@ApplicationScoped
public class Answers {
    private final Map<String, AgentLoop.Answer> answers = new ConcurrentHashMap<>();


    public void put(final String id, final AgentLoop.Answer answer) {
        answers.put(id, answer);
    }

    /** The answer for the id, removed from the store, or null if the workflow ended without one. */
    public AgentLoop.Answer take(final String id) {
        return answers.remove(id);
    }
}
