package eu.hansolo.mcp.client.agentic;


/** The CDI event that starts the agent's workflow. {@code id} ties the answer to the request that fired it. */
public record Question(String id, String text) {
}
