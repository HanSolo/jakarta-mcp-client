package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.hansolo.mcp.client.internal.OAuthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client credentials against an in-process resource server, protected resource metadata, authorization server metadata and token endpoint. */
class OAuthTest {

    private HttpServer           server;
    private String               base;
    private final List<String>   tokenForms  = new CopyOnWriteArrayList<>();
    private final List<String>   basicAuth   = new CopyOnWriteArrayList<>();
    private volatile String      resourceAdvertised;
    private volatile String      requiredScope;
    private volatile boolean     refuseAll;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        base               = "http://localhost:" + server.getAddress().getPort();
        resourceAdvertised = base + "/mcp";
        server.createContext("/.well-known/oauth-protected-resource/mcp", ex -> reply(ex, 200, "application/json", "{\"resource\":\"" + resourceAdvertised + "\",\"authorization_servers\":[\"" + base + "/as\"],\"scopes_supported\":[\"read\"]}", null));
        server.createContext("/.well-known/oauth-authorization-server/as", ex -> reply(ex, 200, "application/json", "{\"issuer\":\"" + base + "/as\",\"token_endpoint\":\"" + base + "/token\"}", null));
        server.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenForms.add(URLDecoder.decode(form, StandardCharsets.UTF_8));
            basicAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String scope = Pattern.compile("scope=([^&]*)").matcher(form).results().map(r -> URLDecoder.decode(r.group(1), StandardCharsets.UTF_8)).findFirst().orElse("");
            reply(ex, 200, "application/json", "{\"access_token\":\"tok-" + tokenForms.size() + "|" + scope + "\",\"token_type\":\"Bearer\",\"expires_in\":600}", null);
        });
        server.createContext("/mcp", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher id  = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
            id.find();
            if (auth == null) {
                reply(ex, 401, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Authentication required\"}}", "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\", scope=\"read\"");
            } else if (refuseAll) {
                reply(ex, 403, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Insufficient scope\"}}", "Bearer error=\"insufficient_scope\", scope=\"read\"");
            } else if (requiredScope != null && !auth.contains(requiredScope)) {
                reply(ex, 403, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Insufficient scope\"}}", "Bearer error=\"insufficient_scope\", scope=\"" + requiredScope + "\", resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\"");
            } else {
                reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":{\"tools\":[]}}", null);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String type, String body, String challenge) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        if (challenge != null) {
            ex.getResponseHeaders().add("WWW-Authenticate", challenge);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private McpClient client(String... scopes) {
        return McpClient.builder(URI.create(base + "/mcp")).oauthClientCredentials("my id", "s3cr:t", scopes).build();
    }

    @Test
    void a401LeadsToDiscoveryATokenAndOneRetry() {
        McpClient client = client();
        client.listTools();
        client.listTools();
        assertEquals(1, tokenForms.size(), "the second call reuses the cached token");
        String form = tokenForms.get(0);
        assertTrue(form.contains("grant_type=client_credentials"));
        assertTrue(form.contains("resource=" + base + "/mcp"), form);
        assertTrue(form.contains("scope=read"), "the scope of the challenge is requested: " + form);
        String expected = "Basic " + Base64.getEncoder().encodeToString("my+id:s3cr%3At".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, basicAuth.get(0), "client id and secret are url-encoded before Basic");
    }

    @Test
    void anInsufficientScopeStepsUpOnceWithTheUnionOfScopes() {
        McpClient client = client("read");
        client.listTools();
        requiredScope = "write";
        client.listTools();
        assertEquals(2, tokenForms.size());
        assertTrue(tokenForms.get(1).contains("scope=read write"), tokenForms.get(1));
    }

    @Test
    void aScopeTheTokenWasAlreadyAskedForDoesNotLoop() {
        McpClient client = client("read");
        client.listTools();
        refuseAll = true;
        McpClientException e = assertThrows(McpClientException.class, client::listTools);
        assertEquals(403, e.httpStatus());
        assertEquals(1, tokenForms.size(), "no new token for scopes that were already requested");
    }

    @Test
    void aTokenEndpointGivenUpFrontSkipsDiscovery() {
        McpClient client = McpClient.builder(URI.create(base + "/mcp")).oauthClientCredentials("id", "secret", "read").oauthTokenEndpoint(URI.create(base + "/token")).build();
        client.listTools();
        assertEquals(1, tokenForms.size());
    }

    @Test
    void metadataForAnotherResourceIsRefused() {
        resourceAdvertised = "http://localhost:1/other";
        McpClientException e = assertThrows(McpClientException.class, () -> client().listTools());
        assertTrue(e.getMessage().contains("another resource"), e.getMessage());
        assertTrue(tokenForms.isEmpty(), "no token is requested from an unverified server");
    }

    @Test
    void challengeParsing() {
        Map<String, String> p = OAuthClient.parseChallenge("Bearer error=\"insufficient_scope\", scope=\"a b\", resource_metadata=\"https://h/x\"");
        assertEquals("insufficient_scope", p.get("error"));
        assertEquals("a b", p.get("scope"));
        assertEquals("https://h/x", p.get("resource_metadata"));
        assertTrue(OAuthClient.parseChallenge("Basic realm=\"x\"").isEmpty());
        assertTrue(OAuthClient.parseChallenge(null).isEmpty());
    }

    @Test
    void plainHttpToARemoteHostIsRefusedForTheTokenEndpoint() {
        assertThrows(McpClientException.class, () -> McpClient.builder(URI.create(base + "/mcp")).oauthClientCredentials("id", "secret").oauthTokenEndpoint(URI.create("http://example.com/token")).build());
        assertFalse(new ArrayList<>(tokenForms).stream().anyMatch(f -> f.contains("secret")), "the secret never goes in the form");
    }
}
