package eu.hansolo.mcp.client.agentic;

import eu.hansolo.mcp.client.McpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/** Needs the mcp-example server: {@code -Dmcp.example.url=http://localhost:8081/mcp}. The scripted model and the real client: no API key, no network beyond the server. */
class AgentLoopIT {

    @Test
    void addTwoAndThreeEndsInFiveWithExactlyOneAddCall() {
        String url = System.getProperty("mcp.example.url");
        assumeTrue(url != null, "mcp.example.url not set");
        McpClient        client = McpClient.builder(URI.create(url)).build();
        AgentLoop.Answer answer = new AgentLoop(FakeModel::reply, new McpToolBackend(client), 5).run("add 2 and 3");
        assertFalse(answer.truncated());
        assertEquals(1, answer.calls().size());
        assertEquals("add", answer.calls().get(0).name());
        assertFalse(answer.calls().get(0).isError());
        assertTrue(answer.text().contains("5"), answer.text());
    }
}
