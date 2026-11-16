package eu.hansolo.mcp.client.agentplain;

import jakarta.enterprise.context.ApplicationScoped;


/**
 * Chooses the model access from the environment. {@code AGENT_FAKE_LLM=true} gives the scripted {@link FakeLlm}; otherwise {@code ANTHROPIC_API_KEY} and
 * {@code ANTHROPIC_MODEL} are required. {@code AGENT_MAX_ROUNDS} (default 8) limits the rounds, {@code ANTHROPIC_MAX_TOKENS} (default 1024) the reply length.
 */
@ApplicationScoped
public class LlmProvider {
    private volatile Llm llm;


    public Llm llm() {
        Llm current = llm;
        if (current == null) {
            synchronized (this) {
                if (llm == null) { llm = create(); }
                current = llm;
            }
        }
        return current;
    }

    public int maxRounds() {
        return intFromEnvironment("AGENT_MAX_ROUNDS", 8);
    }

    private static Llm create() {
        if ("true".equalsIgnoreCase(System.getenv("AGENT_FAKE_LLM"))) { return new FakeLlm(); }
        String key   = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv("ANTHROPIC_MODEL");
        if (key == null || key.isBlank() || model == null || model.isBlank()) { throw new IllegalStateException("Set ANTHROPIC_API_KEY and ANTHROPIC_MODEL, or AGENT_FAKE_LLM=true for the scripted model."); }
        return new AnthropicLlm(key, model, intFromEnvironment("ANTHROPIC_MAX_TOKENS", 1024));
    }

    private static int intFromEnvironment(final String name, final int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
