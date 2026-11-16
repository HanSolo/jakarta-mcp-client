package eu.hansolo.mcp.client.agentic;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.cdi.McpConnection;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.HandleException;
import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Trigger;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Logger;


/**
 * Answers a question with the tools of an MCP server. A Jakarta Agentic AI agent is a CDI bean; without a scope annotation it is {@code @WorkflowScoped}, one instance per question.
 * The model comes from the runtime ({@link LargeLanguageModel}), the tools from the injected {@link McpClient}. The 1.0 facade has no tool calling, so {@link AgentLoop} runs the
 * tools through the model's text replies.
 */
@Agent(name = "mcp-agent", description = "Answers a question with the tools of an MCP server.")
public class McpAgent {
    private static final Logger LOGGER = Logger.getLogger(McpAgent.class.getName());

    @Inject
    @McpConnection("example")
    McpClient client;

    @Inject
    Answers answers;

    @Inject
    @ConfigProperty(name = "agent.max-rounds", defaultValue = "8")
    int maxRounds;

    ToolBackend backend;


    /** Starts the workflow: every {@code Event<Question>.fire(...)} runs one. */
    @Trigger
    void onQuestion(final Question question) {
        LOGGER.info(() -> "[TRIGGER] " + question.id() + ": " + question.text());
    }

    /** The tool loop. The {@link AgentLoop.Answer} it returns becomes workflow state for the later phases. */
    @Action
    AgentLoop.Answer ask(final Question question, final LargeLanguageModel llm) {
        AgentLoop.Answer answer = new AgentLoop(prompt -> llm.query(prompt), backend(), maxRounds).run(question.text());
        LOGGER.info(() -> "[ACTION] " + question.id() + ": " + answer.calls().size() + " tool call(s) in " + answer.rounds() + " round(s)");
        return answer;
    }

    /** The model is down, times out or answers something unusable. Returning normally recovers the workflow, which then still runs {@link #publish}. */
    @HandleException
    void llmFailed(final LLMException e, final Question question) {
        LOGGER.warning(() -> "[EXCEPTION] " + question.id() + ": " + e.getMessage());
        answers.put(question.id(), AgentLoop.Answer.failed(e.getMessage()));
    }

    /** Ends the workflow. After a recovered failure there is no answer in the workflow state, and the failure is already stored. */
    @Outcome
    void publish(final Question question, final AgentLoop.Answer answer) {
        if (answer != null) { answers.put(question.id(), answer); }
    }

    ToolBackend backend() {
        if (backend == null) { backend = new McpToolBackend(client); }
        return backend;
    }
}
