package eu.hansolo.mcp.client.cdi;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads settings the way the server library does, so that it works on every Jakarta EE server: MicroProfile Config if the server has it (then also {@code microprofile-config.properties}
 * and the other config sources), otherwise the system property, otherwise the environment variable ({@code mcp.client.tickets.url} as {@code MCP_CLIENT_TICKETS_URL}).
 */
final class Settings {

    private final Function<String, String> microProfile = microProfile();


    Optional<String> get(String key) {
        String value = microProfile == null ? null : microProfile.apply(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        if (value == null) {
            value = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Function<String, String> microProfile() {
        try {
            Class.forName("org.eclipse.microprofile.config.ConfigProvider");
            return MicroProfileLookup.create();
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;    // WildFly and Open Liberty have no MicroProfile Config unless it is switched on
        }
    }
}
