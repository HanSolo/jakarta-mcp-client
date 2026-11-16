package eu.hansolo.mcp.client;

import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs mcp-example-tasks with {@code MCP_TASKS_ENABLED=true}: {@code -Dmcp.tasks.url=http://localhost:8082/mcp}. */
class TasksIT {

    private static URI endpoint() {
        String url = System.getProperty("mcp.tasks.url");
        assumeTrue(url != null, "mcp.tasks.url not set");
        return URI.create(url);
    }

    @Test
    void aReportTaskAsksForPublishingAndCompletes() {
        McpClient client = McpClient.builder(endpoint()).tasks().elicitation(request -> Elicitation.Response.accept(Json.createObjectBuilder().add("publish", true).build())).build();
        ToolResult first = client.callTool("build_report", Json.createObjectBuilder().add("topic", "client test").add("sections", 2).build());
        assertTrue(first.isTask(), first.raw().toString());
        TaskState done = client.awaitTask(first.task().taskId(), Duration.ofSeconds(60));
        assertTrue(done.completed(), done.raw().toString());
        assertTrue(done.result().toString().contains("published"), done.result().toString());
    }

    @Test
    void callToolAndAwaitHidesTheTask() {
        McpClient client = McpClient.builder(endpoint()).tasks().elicitation(request -> Elicitation.Response.accept(Json.createObjectBuilder().add("publish", true).build())).build();
        ToolResult result = client.callToolAndAwait("build_report", Json.createObjectBuilder().add("topic", "client test").add("sections", 1).build(), null, Duration.ofSeconds(60));
        assertTrue(result.complete());
        assertTrue(result.raw().toString().contains("published"), result.raw().toString());
    }

    @Test
    void cancellingEndsTheTaskAsCancelled() {
        McpClient client = McpClient.builder(endpoint()).tasks().build();
        TaskState task   = client.callTool("build_report", Json.createObjectBuilder().add("topic", "cancel me").add("sections", 8).build()).task();
        client.cancelTask(task.taskId());
        TaskState end = client.awaitTask(task.taskId(), Duration.ofSeconds(30));
        assertEquals("cancelled", end.status(), end.raw().toString());
    }

    @Test
    void aClientWithoutTheExtensionGetsThePlainResult() {
        ToolResult result = McpClient.builder(endpoint()).build().callTool("build_report", Json.createObjectBuilder().add("topic", "inline").add("sections", 1).build());
        assertFalse(result.isTask());
        assertTrue(result.complete(), result.raw().toString());
    }
}
