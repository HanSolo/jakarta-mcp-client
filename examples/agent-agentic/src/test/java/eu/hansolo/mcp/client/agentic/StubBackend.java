package eu.hansolo.mcp.client.agentic;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;


/** A tool backend with one tool, {@code add}, and no server. */
final class StubBackend implements ToolBackend {

    @Override public JsonArray tools() {
        return Json.createArrayBuilder().add(Json.createObjectBuilder().add("name", "add").add("description", "Adds two numbers").add("input_schema", Json.createObjectBuilder().add("type", "object"))).build();
    }

    @Override public Outcome call(final String name, final JsonObject arguments) {
        return new Outcome("{\"sum\":" + (arguments.getJsonNumber("a").doubleValue() + arguments.getJsonNumber("b").doubleValue()) + "}", false);
    }
}
