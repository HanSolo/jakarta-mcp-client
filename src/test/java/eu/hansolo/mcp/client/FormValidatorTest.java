package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.hansolo.mcp.client.internal.FormValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormValidatorTest {

    private static final JsonObject SCHEMA = Json.createReader(new StringReader("""
            {"type":"object","required":["email","age"],"properties":{
              "email":{"type":"string","format":"email","maxLength":20},
              "age":{"type":"integer","minimum":18,"maximum":120},
              "score":{"type":"number"},
              "news":{"type":"boolean"},
              "plan":{"type":"string","oneOf":[{"const":"free","title":"Free"},{"const":"pro","title":"Pro"}]},
              "tags":{"type":"array","minItems":1,"maxItems":2,"items":{"anyOf":[{"const":"a"},{"const":"b"},{"const":"c"}]}}}}
            """)).readObject();

    private static List<String> check(String content) {
        return FormValidator.problems(SCHEMA, Json.createReader(new StringReader(content)).readObject());
    }

    @Test
    void aFittingAnswerHasNoProblems() {
        assertEquals(List.of(), check("{\"email\":\"ada@example.com\",\"age\":36,\"score\":1.5,\"news\":true,\"plan\":\"pro\",\"tags\":[\"a\",\"b\"]}"));
    }

    @Test
    void requiredValuesAndUnknownPropertiesAreReported() {
        List<String> problems = check("{\"email\":\"ada@example.com\",\"other\":1}");
        assertTrue(problems.contains("'age' is required"), problems.toString());
        assertTrue(problems.contains("unexpected property 'other'"), problems.toString());
    }

    @Test
    void typesRangesFormatsAndChoicesAreChecked() {
        assertTrue(check("{\"email\":\"nope\",\"age\":36}").get(0).contains("valid email"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":17}").get(0).contains("at least 18"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36.5}").get(0).contains("whole number"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":\"36\"}").get(0).contains("must be a number"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"news\":\"yes\"}").get(0).contains("true or false"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"plan\":\"gold\"}").get(0).contains("one of"));
        assertTrue(check("{\"email\":\"averyveryveryverylong@example.com\",\"age\":36}").get(0).contains("at most 20"));
    }

    @Test
    void multipleChoicesAreChecked() {
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"tags\":[]}").get(0).contains("at least 1"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"tags\":[\"a\",\"b\",\"c\"]}").get(0).contains("at most 2"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"tags\":[\"z\"]}").get(0).contains("not one of"));
        assertTrue(check("{\"email\":\"ada@example.com\",\"age\":36,\"tags\":[\"a\",\"a\"]}").get(0).contains("twice"));
    }

    @Test
    void aPlainConfirmationAcceptsOnlyEmptyContent() {
        JsonObject confirm = Json.createReader(new StringReader("{\"type\":\"object\",\"properties\":{}}")).readObject();
        assertEquals(List.of(), FormValidator.problems(confirm, Json.createObjectBuilder().build()));
        assertEquals(1, FormValidator.problems(confirm, Json.createObjectBuilder().add("x", 1).build()).size());
    }

    // ---- through the client ------------------------------------------------------------------------------------

    @Test
    void anAnswerThatDoesNotFitIsNeverSent() throws IOException {
        List<String> methods = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", (HttpExchange exchange) -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            methods.add(body.contains("inputResponses") ? "retry" : exchange.getRequestHeaders().getFirst("Mcp-Method"));
            int    id     = Integer.parseInt(body.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
            String result = "tools/list".equals(exchange.getRequestHeaders().getFirst("Mcp-Method"))
                    ? "{\"tools\":[],\"ttlMs\":60000}"
                    : "{\"resultType\":\"input_required\",\"inputRequests\":{\"c\":{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\",\"message\":\"Mail?\",\"requestedSchema\":{\"type\":\"object\",\"required\":[\"email\"],\"properties\":{\"email\":{\"type\":\"string\",\"format\":\"email\"}}}}}}}";
            byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            McpClient client = McpClient.builder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")).elicitation(request -> Elicitation.Response.accept(Json.createObjectBuilder().add("email", "nope").build())).build();
            McpClientException e = assertThrows(McpClientException.class, () -> client.callTool("t", null));
            assertTrue(e.getMessage().contains("valid email"), e.getMessage());
            assertTrue(!methods.contains("retry"), "nothing was sent after the bad answer: " + methods);
        } finally {
            server.stop(0);
        }
    }
}
