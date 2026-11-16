package eu.hansolo.mcp.client.agentic;

import jakarta.json.Json;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/** A scripted model for runs without a key: "add 2 and 3" becomes one call of the tool {@code add}, and the tool's result becomes the answer. It speaks the protocol of {@link AgentLoop}. */
public final class FakeModel {
    private static final Pattern ADD = Pattern.compile("add\\s+(-?\\d+(?:\\.\\d+)?)\\s+and\\s+(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);


    private FakeModel() {}


    /** The reply to one message of the conversation, which is all this model looks at. */
    public static String reply(final String message) {
        if (message.startsWith(AgentLoop.RESULT_PREFIX)) {
            int    from = message.indexOf('\n') + 1;
            int    to   = message.lastIndexOf("\n" + AgentLoop.CONTINUE);
            return answer("The tool said: " + message.substring(from, to < from ? message.length() : to));
        }
        int      marker  = message.lastIndexOf("Question: ");
        Matcher  matcher = ADD.matcher(marker < 0 ? message : message.substring(marker));
        if (!matcher.find() || !message.contains("\"name\":\"add\"")) { return answer("The fake model only understands \"add 2 and 3\" on a server with a tool named add."); }
        return Json.createObjectBuilder().add("tool", "add").add("arguments", Json.createObjectBuilder().add("a", new BigDecimal(matcher.group(1))).add("b", new BigDecimal(matcher.group(2)))).build().toString();
    }

    private static String answer(final String text) {
        return Json.createObjectBuilder().add("answer", text).build().toString();
    }
}
