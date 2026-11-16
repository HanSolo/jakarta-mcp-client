package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authorization code flow with PKCE against an in-process resource server, authorization server metadata and token endpoint; the "user" is a fake Authorizer. */
class OAuthCodeTest {

    private static final URI REDIRECT = URI.create("http://127.0.0.1:9/cb");

    private HttpServer                server;
    private String                    base;
    private final List<String>        tokenForms    = new CopyOnWriteArrayList<>();
    private final List<String>        tokenAuth     = new CopyOnWriteArrayList<>();
    private final List<URI>           authorizeUrls = new CopyOnWriteArrayList<>();
    private volatile String           lastChallenge = "";
    private volatile boolean          verifierMatched;
    private volatile String           methodsJson   = "[\"S256\"]";
    private volatile int              firstExpires  = 600;
    private volatile String           stateOverride;
    private volatile String           errorAnswer;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/.well-known/oauth-protected-resource/mcp", ex -> reply(ex, 200, "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "/as\"],\"scopes_supported\":[\"read\"]}", null));
        server.createContext("/.well-known/oauth-authorization-server/as", ex -> reply(ex, 200, "{\"issuer\":\"" + base + "/as\",\"authorization_endpoint\":\"" + base + "/as/authorize\",\"token_endpoint\":\"" + base + "/token\"" + (methodsJson == null ? "" : ",\"code_challenge_methods_supported\":" + methodsJson) + "}", null));
        server.createContext("/token", ex -> {
            String form = URLDecoder.decode(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            tokenForms.add(form);
            tokenAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            Matcher verifier = Pattern.compile("code_verifier=([^&]*)").matcher(form);
            if (form.contains("grant_type=authorization_code") && verifier.find()) {
                try {
                    verifierMatched = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.group(1).getBytes(StandardCharsets.US_ASCII))).equals(lastChallenge);
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            int expires = form.contains("grant_type=authorization_code") ? firstExpires : 600;
            reply(ex, 200, "{\"access_token\":\"tok" + tokenForms.size() + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expires + ",\"refresh_token\":\"r" + tokenForms.size() + "\"}", null);
        });
        server.createContext("/mcp", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher id  = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
            id.find();
            if (auth == null) {
                reply(ex, 401, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Authentication required\"}}", "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\", scope=\"read\"");
            } else {
                reply(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":{\"tools\":[]}}", null);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body, String challenge) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (challenge != null) {
            ex.getResponseHeaders().add("WWW-Authenticate", challenge);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** The user: reads state and challenge from the request and sends the browser back with a code. */
    private final Authorizer user = new Authorizer() {
        @Override
        public URI redirectUri() {
            return REDIRECT;
        }

        @Override
        public URI authorize(URI url) {
            authorizeUrls.add(url);
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : url.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            lastChallenge = query.get("code_challenge");
            if (errorAnswer != null) {
                return URI.create(REDIRECT + "?error=" + errorAnswer + "&state=" + query.get("state"));
            }
            return URI.create(REDIRECT + "?code=abc&state=" + (stateOverride != null ? stateOverride : query.get("state")) + "&iss=" + base + "/as");
        }
    };

    private McpClient client() {
        return McpClient.builder(URI.create(base + "/mcp")).oauthAuthorizationCode("my-app", user).build();
    }

    @Test
    void aSignInGivesATokenAndOneRetry() {
        McpClient client = client();
        client.listTools();
        client.listTools();
        assertEquals(1, authorizeUrls.size(), "the second call reuses the token");
        String url = authorizeUrls.get(0).toString();
        assertTrue(url.startsWith(base + "/as/authorize?response_type=code&client_id=my-app"), url);
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("&state=") && url.contains("scope=read"), url);
        assertTrue(url.contains("resource=" + java.net.URLEncoder.encode(base + "/mcp", StandardCharsets.UTF_8)), url);
        String form = tokenForms.get(0);
        assertTrue(form.contains("grant_type=authorization_code") && form.contains("code=abc") && form.contains("redirect_uri=" + REDIRECT) && form.contains("client_id=my-app") && form.contains("resource=" + base + "/mcp"), form);
        assertNull(tokenAuth.get(0), "a public client sends no secret");
        assertTrue(verifierMatched, "the verifier hashes to the challenge of the request");
    }

    @Test
    void anExpiredTokenIsRefreshedWithoutAskingTheUserAgain() {
        firstExpires = 1;   // shorter than the safety margin: expired as soon as it is stored
        McpClient client = client();
        client.listTools();
        client.listTools();
        assertEquals(1, authorizeUrls.size());
        assertTrue(tokenForms.stream().anyMatch(f -> f.contains("grant_type=refresh_token") && f.contains("refresh_token=r1")), tokenForms.toString());
    }

    @Test
    void anAnswerWithAnotherStateIsRefused() {
        stateOverride = "forged";
        McpClientException e = assertThrows(McpClientException.class, () -> client().listTools());
        assertTrue(e.getMessage().contains("state"), e.getMessage());
        assertTrue(tokenForms.isEmpty(), "the code of a forged answer is never redeemed");
    }

    @Test
    void aServerWithoutPkceIsRefusedBeforeTheUserIsAsked() {
        methodsJson = null;
        McpClientException e = assertThrows(McpClientException.class, () -> client().listTools());
        assertTrue(e.getMessage().contains("PKCE"), e.getMessage());
        assertTrue(authorizeUrls.isEmpty());
    }

    @Test
    void aRefusalByTheUserIsReported() {
        errorAnswer = "access_denied";
        McpClientException e = assertThrows(McpClientException.class, () -> client().listTools());
        assertTrue(e.getMessage().contains("access_denied"), e.getMessage());
    }

    @Test
    void aRedirectUriOverPlainHttpToAnotherHostIsRefused() {
        Authorizer remote = new Authorizer() {
            @Override
            public URI redirectUri() {
                return URI.create("http://example.com/cb");
            }

            @Override
            public URI authorize(URI url) {
                throw new AssertionError("must not be asked");
            }
        };
        assertThrows(McpClientException.class, () -> McpClient.builder(URI.create(base + "/mcp")).oauthAuthorizationCode("my-app", remote).build().listTools());
    }
}
