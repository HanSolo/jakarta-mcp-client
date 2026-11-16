package eu.hansolo.mcp.client.cdi;

import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifies an injected {@code McpClient}. {@code @Inject @McpConnection("tickets") McpClient client;} reads its settings from the keys
 * {@code mcp.client.tickets.url} (required), {@code .client-id}, {@code .client-secret}, {@code .scopes} (space separated), {@code .token-endpoint}, {@code .bearer-token}
 * and {@code .timeout-seconds}, taken from MicroProfile Config if the server has it, otherwise from system properties or environment variables ({@code MCP_CLIENT_TICKETS_URL}). Without a name the keys start with {@code mcp.client.url} and so on. {@code client-id} selects OAuth client credentials; {@code bearer-token} a fixed token.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface McpConnection {

    /** The connection name; empty for the unnamed default connection. Not part of the qualifier's identity, so that one producer serves every name; it reads the name from the injection point. */
    @Nonbinding
    String value() default "";
}
