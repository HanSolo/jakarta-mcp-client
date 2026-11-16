package eu.hansolo.mcp.client;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The authorization code flow with PKCE against the Keycloak and tickets stack ({@code -Dmcp.auth.url=...}). Client {@code mcp-inspector} (public, PKCE, redirect
 * {@code http://127.0.0.1:6274/*}), user alice. The "browser" is a few lines of HTTP that fill in Keycloak's login form.
 */
class AuthCodeIT {

    private static String url;

    @BeforeAll
    static void gate() {
        url = System.getProperty("mcp.auth.url");
        assumeTrue(url != null, "set -Dmcp.auth.url to run");
    }

    /** Opens the authorization URL like a browser would: login page, form post, and the redirect to the loopback listener. */
    private static void signInAsAlice(URI authorizationUrl) {
        try {
            HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> page = browser.send(HttpRequest.newBuilder(authorizationUrl).GET().build(), HttpResponse.BodyHandlers.ofString());
            Matcher action = Pattern.compile("action=\"([^\"]*login-actions/authenticate[^\"]*)\"").matcher(page.body());
            if (!action.find()) {
                throw new IllegalStateException("No login form on the page (status " + page.statusCode() + ")");
            }
            URI form = URI.create(action.group(1).replace("&amp;", "&"));
            // cookies by hand: java.net.CookieManager is unreliable for localhost
            String cookies = String.join("; ", page.headers().allValues("Set-Cookie").stream().map(c -> c.contains(";") ? c.substring(0, c.indexOf(';')) : c).toList());
            HttpResponse<String> login = browser.send(HttpRequest.newBuilder(form).header("Cookie", cookies).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=alice&password=alice&credentialId=")).build(), HttpResponse.BodyHandlers.ofString());
            String location = login.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("The login was not answered with a redirect (status " + login.statusCode() + ", cookies sent: " + (cookies.isEmpty() ? "none" : cookies.replaceAll("=[^;]*", "=...")) + "): " + login.body().replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim().substring(0, Math.min(300, login.body().replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim().length()))));
            browser.send(HttpRequest.newBuilder(URI.create(location)).GET().build(), HttpResponse.BodyHandlers.ofString());   // delivers code and state to the loopback listener
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aUserSignsInOnceAndTheTokenIsReused() {
        AtomicInteger signIns = new AtomicInteger();
        try (LoopbackAuthorizer authorizer = LoopbackAuthorizer.create(6274, link -> {
            signIns.incrementAndGet();
            signInAsAlice(link);
        }, Duration.ofSeconds(30))) {
            McpClient client = McpClient.builder(URI.create(url)).oauthAuthorizationCode("mcp-inspector", authorizer).build();
            assertTrue(client.tools().stream().anyMatch(t -> t.getString("name").equals("search_tickets")));
            assertEquals(false, client.callTool("search_tickets", jakarta.json.Json.createObjectBuilder().add("limit", 1).build()).isError());
            assertEquals(1, signIns.get());
        }
    }
}
