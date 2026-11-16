package eu.hansolo.mcp.client.internal;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamHeadersTest {

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    private static final JsonObject SCHEMA = json("""
            {"type":"object","properties":{
              "region":{"type":"string","x-mcp-header":"Region"},
              "count":{"type":"integer","x-mcp-header":"Count"},
              "urgent":{"type":"boolean","x-mcp-header":"Urgent"},
              "target":{"type":"object","properties":{"zone":{"type":"string","x-mcp-header":"Zone"}}},
              "ratio":{"type":"number","x-mcp-header":"Ratio"},
              "note":{"type":"string"}}}
            """);

    @Test
    void declaredFindsNestedAndSkipsInvalidTypes() {
        List<ParamHeaders.Param> params = ParamHeaders.declared(SCHEMA);
        assertEquals(List.of("Mcp-Param-Region", "Mcp-Param-Count", "Mcp-Param-Urgent", "Mcp-Param-Zone"), params.stream().map(ParamHeaders.Param::headerName).toList());
        assertEquals(List.of("target", "zone"), params.get(3).path());
    }

    @Test
    void headersMirrorValuesAndEncodeNonAscii() {
        JsonObject args = json("{\"region\":\"zürich\",\"count\":3,\"urgent\":false,\"target\":{\"zone\":\"a\"},\"note\":\"x\"}");
        Map<String, String> headers = ParamHeaders.headers(ParamHeaders.declared(SCHEMA), args);
        assertEquals("=?base64?esO8cmljaA==?=", headers.get("Mcp-Param-Region"));
        assertEquals("3", headers.get("Mcp-Param-Count"));
        assertEquals("false", headers.get("Mcp-Param-Urgent"));
        assertEquals("a", headers.get("Mcp-Param-Zone"));
        assertEquals(4, headers.size());
    }

    @Test
    void missingNullAndFractionalArgumentsGetNoHeader() {
        Map<String, String> headers = ParamHeaders.headers(ParamHeaders.declared(SCHEMA), json("{\"region\":null,\"count\":2.5}"));
        assertTrue(headers.isEmpty());
    }
}
