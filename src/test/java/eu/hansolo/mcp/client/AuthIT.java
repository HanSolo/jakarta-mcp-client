package eu.hansolo.mcp.client;

import jakarta.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Against the Keycloak + tickets stack of the server repo: {@code -Dmcp.auth.url=http://localhost:8080/mcp}. Realm {@code mcp}, client {@code mcp-test}
 * (secret {@code mcp-test-secret}), and {@code mcp-no-audience} whose tokens the server must refuse.
 */
class AuthIT {

    private static String url;

    @BeforeAll
    static void gate() {
        url = System.getProperty("mcp.auth.url");
        assumeTrue(url != null, "set -Dmcp.auth.url to run");
    }

    @Test
    void clientCredentialsWorkWithoutAnyConfiguredToken() {
        McpClient client = McpClient.builder(URI.create(url)).oauthClientCredentials("mcp-test", "mcp-test-secret").build();
        assertTrue(client.tools().stream().anyMatch(t -> t.getString("name").equals("search_tickets")));
    }

    @Test
    void aWriteToolStepsUpFromReadOnlyScopes() {
        McpClient client = McpClient.builder(URI.create(url)).oauthClientCredentials("mcp-test", "mcp-test-secret", "mcp:tools", "tickets:read").build();
        ToolResult result = client.callTool("create_ticket", jakarta.json.Json.createObjectBuilder().add("title", "client auth it").add("description", "created by AuthIT").build());
        assertEquals(false, result.isError());
    }

    /** A token that Keycloak issues for mcp-no-audience (scope mcp:tools only) is not bound to the MCP server: the server itself must answer 401 invalid_token. */
    @Test
    void aTokenNotBoundToTheServerIsRejectedByTheServer() throws Exception {
        String issuer = System.getProperty("mcp.auth.issuer", "http://localhost:8180/realms/mcp");
        var    http   = HttpClient.newHttpClient();
        var    form   = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=mcp-no-audience&client_secret=mcp-test-secret&scope=mcp%3Atools")).build();
        var    answer = http.send(form, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        String token = Json.createReader(new StringReader(answer.body())).readObject().getString("access_token");

        McpClient           client = McpClient.builder(URI.create(url)).bearerToken(() -> token).build();
        McpClientException  e      = assertThrows(McpClientException.class, client::tools);
        assertEquals(401, e.httpStatus());
        assertTrue(e.wwwAuthenticate().contains("invalid_token"), e.wwwAuthenticate());
    }

    /** The authorization server refuses scopes the client may not have; the client reports that instead of looping. */
    @Test
    void scopesTheAuthorizationServerRefusesAreReported() {
        McpClient          client = McpClient.builder(URI.create(url)).oauthClientCredentials("mcp-no-audience", "mcp-test-secret").build();
        McpClientException e      = assertThrows(McpClientException.class, client::tools);
        assertTrue(e.getMessage().contains("token"), e.getMessage());
    }
}
