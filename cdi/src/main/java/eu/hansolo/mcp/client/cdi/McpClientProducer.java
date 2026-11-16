package eu.hansolo.mcp.client.cdi;

import eu.hansolo.mcp.client.McpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds one {@code McpClient} per connection name and hands the same instance to every injection point with that name. */
@ApplicationScoped
public class McpClientProducer {

    private final Map<String, McpClient> clients  = new ConcurrentHashMap<>();
    private final Settings                settings = new Settings();


    @Produces
    @Dependent
    @McpConnection
    public McpClient client(InjectionPoint point) {
        McpConnection qualifier = point.getQualifiers().stream().filter(McpConnection.class::isInstance).map(McpConnection.class::cast).findFirst().orElseThrow(() -> new IllegalStateException("@McpConnection is missing at " + point));
        return clients.computeIfAbsent(qualifier.value(), this::create);
    }

    private McpClient create(String name) {
        String prefix = name.isEmpty() ? "mcp.client." : "mcp.client." + name + ".";
        String url    = settings.get(prefix + "url").orElseThrow(() -> new IllegalStateException("Set " + prefix + "url to use @McpConnection(\"" + name + "\")"));
        McpClient.Builder builder = McpClient.builder(URI.create(url));
        settings.get(prefix + "timeout-seconds").ifPresent(seconds -> builder.timeout(Duration.ofSeconds(Long.parseLong(seconds))));
        var clientId = settings.get(prefix + "client-id");
        if (clientId.isPresent()) {
            String   secret = settings.get(prefix + "client-secret").orElseThrow(() -> new IllegalStateException("Set " + prefix + "client-secret"));
            String[] scopes = settings.get(prefix + "scopes").map(text -> text.trim().split("\\s+")).orElse(new String[0]);
            builder.oauthClientCredentials(clientId.get(), secret, scopes);
            settings.get(prefix + "token-endpoint").ifPresent(endpoint -> builder.oauthTokenEndpoint(URI.create(endpoint)));
        } else {
            settings.get(prefix + "bearer-token").ifPresent(token -> builder.bearerToken(() -> token));
        }
        return builder.build();
    }
}
