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

- Runs of 2026-11-16 with `scripts/it.sh --server ...`: WildFly 42 (`latest-jdk21`): 52 unit tests and all 23 integration tests, including Keycloak and `AuthCodeIT`, CDI check ok. Open Liberty (`full-java21-openj9-ubi-minimal`): 52 unit tests and 15 integration tests, CDI check ok; the 8 tests that need the Keycloak and tickets stack skip themselves there (see above). The GitHub Action matrix has not been run yet.
