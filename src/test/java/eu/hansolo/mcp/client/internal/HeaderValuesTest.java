package eu.hansolo.mcp.client.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeaderValuesTest {

    @Test
    void plainAsciiIsUnchanged() {
        assertEquals("echo", HeaderValues.encode("echo"));
    }

    @Test
    void nonAsciiIsBase64WithPadding() {
        assertEquals("=?base64?w7xtbGF1dA==?=", HeaderValues.encode("ümlaut"));
    }

    @Test
    void leadingBlankAndSentinelPrefixAreEncoded() {
        assertEquals("=?base64?IGE=?=", HeaderValues.encode(" a"));
        assertEquals("=?base64?PT9iYXNlNjQ/eA==?=", HeaderValues.encode("=?base64?x"));
    }
}
