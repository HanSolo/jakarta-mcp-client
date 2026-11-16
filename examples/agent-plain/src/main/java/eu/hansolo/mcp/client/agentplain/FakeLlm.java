package eu.hansolo.mcp.client.agentplain;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/** A scripted stand-in for the model, for runs without an API key or a network: "add 2 and 3" becomes one call of the tool {@code add}, and the tool's result becomes the answer. */
public final class FakeLlm implements Llm {
    private static final Pattern ADD = Pattern.compile("add\\s+(-?\\d+(?:\\.\\d+)?)\\s+and\\s+(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);


    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages) {
        JsonObject last = messages.getJsonObject(messages.size() - 1);
        if (last.get("content") instanceof JsonArray blocks && !blocks.isEmpty() && "tool_result".equals(blocks.getJsonObject(0).getString("type", ""))) {
            return text("The tool said: " + blocks.getJsonObject(0).getString("content", ""));
        }
        Matcher matcher = ADD.matcher(messages.getJsonObject(0).getString("content", ""));
        if (!matcher.find() || !hasTool(tools, "add")) { return text("The fake model only understands questions like \"add 2 and 3\" on a server with a tool named add."); }
        JsonObject input = Json.createObjectBuilder().add("a", new BigDecimal(matcher.group(1))).add("b", new BigDecimal(matcher.group(2))).build();
        return Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", "tool_use").add("id", "toolu_fake_1").add("name", "add").add("input", input)).build();
    }

    private static JsonArray text(final String text) {
        return Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", "text").add("text", text)).build();
    }

    private static boolean hasTool(final JsonArray tools, final String name) {
        for (JsonValue tool : tools) {
            if (name.equals(tool.asJsonObject().getString("name", ""))) { return true; }
        }
        return false;
    }
}
