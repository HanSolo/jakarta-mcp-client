package eu.hansolo.mcp.client.agentic;

import jakarta.ai.agent.LargeLanguageModel;


/** The facade of Jakarta Agentic AI 1.0 answered by {@link FakeModel}; only the plain text query is supported. */
final class ScriptedModel implements LargeLanguageModel {

    @Override public String query(final String prompt) {
        return FakeModel.reply(prompt);
    }

    @Override public <T> T query(final String prompt, final Class<T> resultType) {
        throw new UnsupportedOperationException();
    }

    @Override public String query(final String prompt, final Object... parameters) {
        throw new UnsupportedOperationException();
    }

    @Override public <T> T query(final String prompt, final Class<T> resultType, final Object... parameters) {
        throw new UnsupportedOperationException();
    }

    @Override public <T> T unwrap(final Class<T> implClass) {
        throw new IllegalArgumentException("Cannot unwrap to " + implClass.getName());
    }
}
