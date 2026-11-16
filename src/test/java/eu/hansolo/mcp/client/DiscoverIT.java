package eu.hansolo.mcp.client;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a live server, for example mcp-example: {@code -Dmcp.url=http://localhost:8080/mcp} (or {@code mcp.fixture.url}). Skipped without either property. */
class DiscoverIT {

    @Test
    void discoverListsTheCurrentVersion() {
        String url = System.getProperty("mcp.url", System.getProperty("mcp.fixture.url"));
        assumeTrue(url != null, "mcp.url not set");
        JsonObject result = McpClient.builder(URI.create(url)).build().discover();
        assertTrue(result.toString().contains(McpClient.PROTOCOL_VERSION), result.toString());
    }

    @Test
    void toolsListAnswers() {
        String url = System.getProperty("mcp.url", System.getProperty("mcp.fixture.url"));
        assumeTrue(url != null, "mcp.url not set");
        assertTrue(McpClient.builder(URI.create(url)).build().listTools().containsKey("tools"));
    }
}
