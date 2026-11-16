# mcp-jakarta-client

A client for the Model Context Protocol, spec **2026-07-28** (Streamable HTTP, stateless), for Java 21 and Jakarta EE. The core depends only on Jakarta JSON Processing and `java.net.http`; it runs in any JVM and has no CDI. It is the counterpart of `jakarta-mcp-server`.

Not supported on purpose: sampling, roots, logging, dynamic client registration, JSON-RPC batching, the old HTTP+SSE transport and earlier protocol versions.

## Use

```java
McpClient client = McpClient.builder(URI.create("http://localhost:8080/mcp")).build();

client.tools();                                              // cached for the server's ttlMs
ToolResult result = client.callTool("search_tickets", Json.createObjectBuilder().add("text", "printer").build());
JsonObject prompt = client.getPrompt("triage", Map.of("ticketId", "1"));
JsonObject ticket = client.readResource("ticket://1");
```

| Need | API |
|---|---|
| Per-request protocol headers, `Mcp-Param-*` mirroring from `x-mcp-header` | automatic in `callTool` |
| Progress, cancellation | `callTool(name, args, ProgressListener)`, `startToolCall(...).cancel()` |
| Asking the user (elicitation, form and URL) | `builder.elicitation(handler, modes...)`; the multi round-trip loop is automatic |
| Tasks | `builder.tasks()`, then `getTask`, `updateTask`, `cancelTask`, `awaitTask`, `callToolAndAwait` |
| Argument completion | `completePrompt`, `completeResource` |
| Change notifications | `listen(ListenFilter, McpListener)` (reconnects with backoff); `keepListsFresh()` drops cached lists on `list_changed` |
| OAuth, client credentials | `builder.oauthClientCredentials(id, secret, scopes...)`; discovery, token caching and scope step-up are automatic |
| OAuth, a user signs in (authorization code with PKCE) | `builder.oauthAuthorizationCode(clientId, authorizer, scopes...)`, with `LoopbackAuthorizer.create()` for programs on the user's machine |
| Fixed token | `builder.bearerToken(Supplier<String>)` |

Authorization: the client reads the 401 challenge, finds the authorization server through the protected resource metadata (RFC 9728) and the authorization server metadata, and requests a token with `resource=<endpoint>` (RFC 8707). A 403 `insufficient_scope` is answered once with a token for the larger scope set. http is accepted for localhost only. The authorization code flow (public client registered in advance, PKCE S256 required, `state` and the RFC 9207 issuer checked, refresh token used) is available through an `Authorizer`; dynamic client registration is not.

## CDI

`cdi/` builds `mcp-jakarta-client-cdi`: `@Inject @McpConnection("tickets") McpClient client;` with the settings (MicroProfile Config where the server has it, otherwise system properties or environment variables) `mcp.client.tickets.url`, `.client-id`, `.client-secret`, `.scopes`, `.token-endpoint`, `.bearer-token`, `.timeout-seconds`. Install the core first (`mvn install`), then build `cdi/`. `cdi-it/` is a test WAR (`GET /api/tools`) for checking the injection on a real server; see its pom.

## Example: tool-calling agent

The main use of the library is a Jakarta backend in which an LLM uses an MCP server as its tools. `examples/` has it twice, as separate WARs (not part of the published artifacts, not in the release workflow). Both answer `POST /api/ask` with `{"question": "..."}` and return `{"answer", "rounds", "truncated", "toolCalls": [{"name", "arguments", "result", "isError"}]}`, both get their client with `@Inject @McpConnection("example") McpClient` (URL from `MCP_CLIENT_EXAMPLE_URL`), and both run without an API key against a scripted model, so the same `curl` works against either.

| | `examples/agent-agentic` | `examples/agent-plain` |
|---|---|---|
| Programming model | [Jakarta Agentic AI](https://jakarta.ee/specifications/agentic-ai/1.0/) 1.0.0-M1: `@Agent` with `@Trigger`, `@Action`, `@HandleException`, `@Outcome`; the runtime provides the model (`LargeLanguageModel`) | JAX-RS resource, a small `Llm` interface, `java.net.http` and JSON-P against the Anthropic Messages API |
| Tool calling | A JSON protocol over text: the 1.0 facade only takes and returns text, and the specification has no tool calling and no MCP, so the loop sends the tools in the prompt and parses one JSON object per reply | The model's native `tool_use` and `tool_result` blocks |
| Runs on | Payara only (the first and so far only implementation, from 7.2026.8) | Any Jakarta EE 10 server |
| Model settings | The runtime's MicroProfile Config keys: `payara.agentic.llm.provider` (`anthropic` in the WAR), `PAYARA_AGENTIC_LLM_MODEL`, `ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, optional `ANTHROPIC_BASE_URL` for another server with the same API |
| Without a key | `PAYARA_AGENTIC_LLM_ANTHROPIC_BASE_URL` points the runtime at `/api/fake-anthropic`, a scripted stand-in for the Messages API inside the WAR | `AGENT_FAKE_LLM=true` |
| Reliability with the model | Depends on the model sticking to JSON-only replies; a reply that is not in the agreed shape is taken as the answer | The provider enforces the tool call format |

Both versions have been run against a local `qwen3.8:27b` through Ollama. The agentic one with `PAYARA_AGENTIC_LLM_PROVIDER=ollama`, `PAYARA_AGENTIC_LLM_MODEL=qwen3.8:27b` and `PAYARA_AGENTIC_LLM_OLLAMA_BASE_URL=http://host.docker.internal:11434`; the plain one with `ANTHROPIC_BASE_URL=http://host.docker.internal:11434`, `ANTHROPIC_API_KEY=ollama` and `ANTHROPIC_MODEL=qwen3.8:27b`, because Ollama 0.14 and later speaks the Anthropic Messages API. In both, a tool call with arguments and a tool error that the model reports back worked; the agentic version also refused an out-of-range request by quoting the tool's documented limit. Neither has run against the real Anthropic API, and a smaller model may not keep to the JSON-only replies of the agentic version.

In both, `AGENT_MAX_ROUNDS` (default 8) limits the model replies per question, a tool that fails (an `isError` result or an `McpClientException`) goes back to the model as an error result, and a failing model ends in a 502. Not shown on purpose: streaming the answer and elicitation. Jakarta Agentic AI 1.0.0-M1 is a draft; expect the agentic example to change with the specification.

Run both against the `mcp-example` server of `jakarta-mcp-server` (checked out next to this repository) on Payara Micro, with the scripted models:

```
(cd ../jakarta-mcp-server && mvn -q -DskipTests package -pl mcp-example -am)
mvn -q -DskipTests install && (cd cdi && mvn -q -DskipTests install) && (cd examples/agent-agentic && mvn -q package) && (cd examples/agent-plain && mvn -q package)
docker run -d --name agent-example -p 8081:8080 -v "$PWD/../jakarta-mcp-server/mcp-example/target/mcp-example.war:/opt/payara/deployments/ROOT.war:ro" payara/micro:latest
docker run -d --name agent-agentic -p 8084:8080 --add-host=host.docker.internal:host-gateway -e MCP_CLIENT_EXAMPLE_URL=http://host.docker.internal:8081/mcp -e PAYARA_AGENTIC_LLM_PROVIDER=anthropic -e ANTHROPIC_API_KEY=fake -e PAYARA_AGENTIC_LLM_ANTHROPIC_BASE_URL=http://localhost:8080/api/fake-anthropic -v "$PWD/examples/agent-agentic/target/mcp-client-agent-agentic.war:/opt/payara/deployments/ROOT.war:ro" payara/micro:latest
docker run -d --name agent-plain -p 8085:8080 --add-host=host.docker.internal:host-gateway -e MCP_CLIENT_EXAMPLE_URL=http://host.docker.internal:8081/mcp -e AGENT_FAKE_LLM=true -v "$PWD/examples/agent-plain/target/mcp-client-agent-plain.war:/opt/payara/deployments/ROOT.war:ro" payara/micro:latest
curl -s -X POST -H 'Content-Type: application/json' -d '{"question":"add 2 and 3"}' http://localhost:8084/api/ask
curl -s -X POST -H 'Content-Type: application/json' -d '{"question":"add 2 and 3"}' http://localhost:8085/api/ask
docker rm -f agent-example agent-agentic agent-plain
```

With a real model, start the agentic container without `PAYARA_AGENTIC_LLM_ANTHROPIC_BASE_URL` and with `-e ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY -e PAYARA_AGENTIC_LLM_MODEL=claude-sonnet-5-5`, and the plain container with `-e ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY -e ANTHROPIC_MODEL=claude-sonnet-5-5` instead of `AGENT_FAKE_LLM=true`. `scripts/it.sh` on Payara (the default) builds both WARs, runs them against the scripted models and checks that "add 2 and 3" ends in an answer containing 5 with exactly one `add` call; `AGENT_DEMO=1` also runs the plain one on WildFly and Open Liberty. Each module's `AgentLoopIT` does the same with the real client and without a WAR.

## Build and test

Everything in one terminal: `scripts/it.sh` builds the server WARs (expects the server repo next to this one, or `SERVER_REPO=...`), starts the containers, runs all tests and the CDI check, and stops the containers. `--server payara|wildfly|liberty` picks the application server the WARs run on (default `payara`, or env `MCP_SERVER`; `MCP_IMAGE` overrides the image). `--keep` leaves them running, `--stop` removes them, `--no-build` skips the Maven builds. Logs go to `target/it-logs/`. On Open Liberty the Keycloak and tickets part is left out (the server's tickets example needs a data source there), so `AuthIT`, `AuthCodeIT` and `PromptsResourcesIT` skip themselves. The CDI module reads its settings from MicroProfile Config where the server has it (Payara) and otherwise from system properties and environment variables (WildFly, Open Liberty); the CI workflow runs all three servers as a matrix. The manual way:

```
mvn clean verify
mvn clean verify -Dmcp.fixture.url=... -Dmcp.example.url=... -Dmcp.tasks.url=... -Dmcp.auth.url=...
```

The integration tests skip themselves without their property: `mcp.fixture.url` (the `mcp-it-fixture` WAR), `mcp.example.url` (`mcp-example`), `mcp.tasks.url` (`mcp-example-tasks` with `MCP_TASKS_ENABLED=true`), `mcp.auth.url` (the Keycloak and tickets stack).

## Status

- Runs of 2026-11-16 with `scripts/it.sh` on the author's machine (Docker Desktop, Payara Micro 7.2026.10, Keycloak 26.4): 52 unit tests; 23 integration tests (`AuthCodeIT` passed in its own run after a fix to the test's fake browser, the other 22 in the full run); the CDI check (`@McpConnection` injected into a JAX-RS resource on Payara, one shared instance per name) passed.
- Verified against real servers: discovery, tools, routed headers, progress and cancellation, elicitation, tasks, subscriptions, prompts, resources, completion is covered by a fake only in unit tests plus one integration test against the tickets server, OAuth client credentials with scope step-up, the audience rejection by the server, and the authorization code flow with PKCE against Keycloak.
- Not verified: the CDI module on WildFly and Open Liberty; the idle watchdog against a real server's keep-alive; `LoopbackAuthorizer` with a real browser (the integration test fills in the login form by HTTP); dynamic client registration and other grants are not implemented.
- Nothing is published; the API may change before 0.1.0.

- Runs of 2026-11-16 with `scripts/it.sh --server ...`: WildFly 42 (`latest-jdk21`): 52 unit tests and all 23 integration tests, including Keycloak and `AuthCodeIT`, CDI check ok. Open Liberty (`full-java21-openj9-ubi-minimal`): 52 unit tests and 15 integration tests, CDI check ok; the 8 tests that need the Keycloak and tickets stack skip themselves there (see above). The GitHub Action matrix (Payara, WildFly, Open Liberty) has run green.
- Runs of 2026-11-17 of the two agent examples (`examples/agent-agentic`, `examples/agent-plain`): `scripts/it.sh` on Payara Micro 7.2026.10 with the scripted models (unit tests, `AgentLoopIT` and the check through the deployed WAR); both against a local `qwen3.8:27b` through Ollama (see the example section). Not verified: against the Anthropic API itself, and the agentic example on any runtime other than Payara.
