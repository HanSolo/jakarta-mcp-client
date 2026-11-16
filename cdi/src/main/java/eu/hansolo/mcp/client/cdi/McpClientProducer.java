package eu.hansolo.mcp.client.cdi;

import eu.hansolo.mcp.client.McpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds one {@code McpClient} per connection name and hands the same instance to every injection point with that name. */
@ApplicationScoped
public class McpClientProducer {

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();


    @Produces
    @Dependent
    @McpConnection
    public McpClient client(InjectionPoint point) {
        McpConnection qualifier = point.getQualifiers().stream().filter(McpConnection.class::isInstance).map(McpConnection.class::cast).findFirst().orElseThrow(() -> new IllegalStateException("@McpConnection is missing at " + point));
        return clients.computeIfAbsent(qualifier.value(), this::create);
    }

    private McpClient create(String name) {
        Config config = ConfigProvider.getConfig();
        String prefix = name.isEmpty() ? "mcp.client." : "mcp.client." + name + ".";
        String url    = config.getOptionalValue(prefix + "url", String.class).orElseThrow(() -> new IllegalStateException("Set " + prefix + "url to use @McpConnection(\"" + name + "\")"));
        McpClient.Builder builder = McpClient.builder(URI.create(url));
        config.getOptionalValue(prefix + "timeout-seconds", Long.class).ifPresent(seconds -> builder.timeout(Duration.ofSeconds(seconds)));
        var clientId = config.getOptionalValue(prefix + "client-id", String.class);
        if (clientId.isPresent()) {
            String   secret = config.getOptionalValue(prefix + "client-secret", String.class).orElseThrow(() -> new IllegalStateException("Set " + prefix + "client-secret"));
            String[] scopes = config.getOptionalValue(prefix + "scopes", String.class).map(text -> text.trim().split("\\s+")).orElse(new String[0]);
            builder.oauthClientCredentials(clientId.get(), secret, scopes);
            config.getOptionalValue(prefix + "token-endpoint", String.class).ifPresent(endpoint -> builder.oauthTokenEndpoint(URI.create(endpoint)));
        } else {
            config.getOptionalValue(prefix + "bearer-token", String.class).ifPresent(token -> builder.bearerToken(() -> token));
        }
        return builder.build();
    }
}
