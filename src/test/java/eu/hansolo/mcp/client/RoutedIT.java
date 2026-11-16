package eu.hansolo.mcp.client;

import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs a server with the mcp-it-fixture tool {@code routed_echo}: {@code -Dmcp.fixture.url=http://localhost:PORT/mcp}. */
class RoutedIT {

    @Test
    void mirroredHeadersAreAccepted() {
        String url = System.getProperty("mcp.fixture.url");
        assumeTrue(url != null, "mcp.fixture.url not set");
        McpClient  client = McpClient.builder(URI.create(url)).build();
        ToolResult result = client.callTool("routed_echo", Json.createObjectBuilder().add("region", "zürich").add("count", 3).add("urgent", true).add("target", Json.createObjectBuilder().add("zone", "a")).build());
        assertFalse(result.isError());
        assertEquals("zürich", result.structuredContent().getString("region"));
    }

    @Test
    void unknownToolIsAnErrorWithoutHeaders() {
        String url = System.getProperty("mcp.fixture.url");
        assumeTrue(url != null, "mcp.fixture.url not set");
        McpClient client = McpClient.builder(URI.create(url)).build();
        McpClientException e = org.junit.jupiter.api.Assertions.assertThrows(McpClientException.class, () -> client.callTool("no_such_tool", null));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code());
    }
}
