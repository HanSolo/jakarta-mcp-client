package eu.hansolo.mcp.client.cdiit;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.cdi.McpConnection;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** GET /api/tools: the tool names the injected client sees, one per line, and "same=true" if a second injection point got the same instance. */
@Path("/tools")
@RequestScoped
public class ToolsResource {

    @Inject
    @McpConnection("example")
    private McpClient client;

    @Inject
    @McpConnection("example")
    private McpClient again;


    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String tools() {
        List<String> names = client.tools().stream().map(tool -> tool.getString("name")).toList();
        return String.join("\n", names) + "\nsame=" + (client == again) + "\n";
    }
}
