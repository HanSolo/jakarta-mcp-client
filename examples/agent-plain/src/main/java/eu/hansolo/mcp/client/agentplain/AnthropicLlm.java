package eu.hansolo.mcp.client.agentplain;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


/** The Anthropic Messages API over {@link HttpClient} and JSON-P, without a vendor SDK. One request per call, no streaming. */
public final class AnthropicLlm implements Llm {
    private static final URI    ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String SYSTEM   = "You are a helpful assistant. Use the available tools when they help, and answer briefly.";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String     apiKey;
    private final String     model;
    private final int        maxTokens;


    public AnthropicLlm(final String apiKey, final String model, final int maxTokens) {
        this.apiKey    = apiKey;
        this.model     = model;
        this.maxTokens = maxTokens;
    }


    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages) {
        var body = Json.createObjectBuilder().add("model", model).add("max_tokens", maxTokens).add("system", SYSTEM).add("messages", messages);
        if (!tools.isEmpty()) { body.add("tools", tools); }
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(120)).header("content-type", "application/json").header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").POST(HttpRequest.BodyPublishers.ofString(body.build().toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw new IllegalStateException("The Anthropic API answered with status " + response.statusCode() + ": " + response.body()); }
            return clean(Json.createReader(new StringReader(response.body())).readObject().getJsonArray("content"));
        } catch (IOException e) {
            throw new IllegalStateException("The Anthropic API could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Anthropic API", e);
        }
    }

    /** Keeps only what is sent back to the API in the next request: text and tool_use blocks, without any extra fields. */
    private static JsonArray clean(final JsonArray content) {
        JsonArrayBuilder blocks = Json.createArrayBuilder();
        for (JsonValue value : content) {
            JsonObject block = value.asJsonObject();
            switch (block.getString("type", "")) {
                case "text"     -> blocks.add(Json.createObjectBuilder().add("type", "text").add("text", block.getString("text", "")));
                case "tool_use" -> blocks.add(Json.createObjectBuilder().add("type", "tool_use").add("id", block.getString("id")).add("name", block.getString("name")).add("input", block.getJsonObject("input")));
                default         -> { }
            }
        }
        return blocks.build();
    }
}
