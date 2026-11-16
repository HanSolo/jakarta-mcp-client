package eu.hansolo.mcp.client.cdi;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.function.Function;

/** Only loaded when MicroProfile Config is on the class path (see {@link Settings}). */
final class MicroProfileLookup {

    private MicroProfileLookup() {
    }


    static Function<String, String> create() {
        Config config = ConfigProvider.getConfig();
        return key -> config.getOptionalValue(key, String.class).orElse(null);
    }
}
