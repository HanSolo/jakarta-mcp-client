package eu.hansolo.mcp.client;

import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs the mcp-example server (it has the tool {@code countdown}, which the fixture WAR does not): {@code -Dmcp.example.url=http://localhost:8081/mcp}. */
class ProgressIT {

    private static McpClient client() {
        String url = System.getProperty("mcp.example.url");
        assumeTrue(url != null, "mcp.example.url not set");
        return McpClient.builder(URI.create(url)).build();
    }

    @Test
    void countdownReportsProgressWhileRunning() {
        List<Progress> seen   = new CopyOnWriteArrayList<>();
        ToolResult     result = client().callTool("countdown", Json.createObjectBuilder().add("steps", 3).add("delayMs", 50).build(), seen::add);
        assertEquals(3, seen.size(), seen.toString());
        assertEquals(3.0, seen.get(2).progress());
        assertTrue(result.complete());
        assertEquals("Counted down 3 steps", result.content().getJsonObject(0).getString("text"));
    }

    @Test
    void cancellingStopsTheServerSideTool() throws Exception {
        McpClient      client = client();
        CountDownLatch first  = new CountDownLatch(1);
        ToolCallHandle handle = client.startToolCall("countdown", Json.createObjectBuilder().add("steps", 10).add("delayMs", 500).build(), progress -> first.countDown());
        assertTrue(first.await(10, TimeUnit.SECONDS), "no progress event arrived");
        handle.cancel();
        assertTrue(handle.result().isCancelled());
        assertTrue(client.discover().containsKey("supportedVersions") || !client.discover().isEmpty(), "the server must stay healthy after a cancelled call");
    }
}
