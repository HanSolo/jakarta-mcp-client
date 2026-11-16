#!/usr/bin/env bash
# Starts everything the integration tests need, runs them, and tears it all down again. One terminal, no extra windows.
#
#   scripts/it.sh                          # build, start, test, stop
#   scripts/it.sh --server wildfly         # payara (default), wildfly or liberty: the server the WARs run on
#   scripts/it.sh --keep                   # leave the containers running afterwards
#   scripts/it.sh --stop                   # stop what an earlier --keep run left behind
#   scripts/it.sh --no-build               # skip the Maven builds of the server WARs and of this project's modules
#   SERVER_REPO=/path/to/jakarta-mcp-server scripts/it.sh
#   MVN_ARGS="-Dit.test=AuthCodeIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false" scripts/it.sh   # one integration test only
#
# Ports: 8080 tickets server (OAuth), 8180 Keycloak, 8081 example, 8082 tasks example, 8090 fixture, 8083 CDI test WAR, 8084 agentic agent WAR, 8085 plain agent WAR.
# The agent examples run with a scripted model, no API key. examples/agent-agentic (Jakarta Agentic AI) runs on payara only: no other server implements the specification yet.
# examples/agent-plain (plain Jakarta EE) runs on payara by default; AGENT_DEMO=1 turns it on for the other servers.
# Open Liberty: the tickets example needs a data source there (see the server README), so the Keycloak part (AuthIT, AuthCodeIT, PromptsResourcesIT) is skipped.
# Logs: target/it-logs/ (one file per container, plus maven.log).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_REPO="${SERVER_REPO:-$ROOT/../jakarta-mcp-server}"
LOGS="$ROOT/target/it-logs"
PROJECT=mcpit
KEEP=0
BUILD=1
MVN_ARGS="${MVN_ARGS:-}"
AGENT=0
PLAIN=0
STOP=0
SERVER="${MCP_SERVER:-payara}"
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --no-build) BUILD=0 ;;
    --stop) STOP=1 ;;
    --server) shift; SERVER="${1:-}" ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
  shift
done
case "$SERVER" in
  payara)  IMAGE="payara/micro:latest";                                       PORT=8080; WAR_PATH=/opt/payara/deployments/ROOT.war;                      KEYCLOAK=1 ;;
  wildfly) IMAGE="quay.io/wildfly/wildfly:latest-jdk21";                      PORT=8080; WAR_PATH=/opt/jboss/wildfly/standalone/deployments/ROOT.war;    KEYCLOAK=1 ;;
  liberty) IMAGE="icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal"; PORT=9080; WAR_PATH=/opt/ol/wlp/usr/servers/defaultServer/apps/ROOT.war;    KEYCLOAK=0 ;;
  *) echo "Unknown server '$SERVER': use payara, wildfly or liberty"; exit 2 ;;
esac
IMAGE="${MCP_IMAGE:-$IMAGE}"
[ "$SERVER" = payara ] && AGENT=1
{ [ "$SERVER" = payara ] || [ "${AGENT_DEMO:-0}" = 1 ]; } && PLAIN=1

say() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker is not on the PATH"
docker info >/dev/null 2>&1 || die "the Docker daemon is not running"
[ -f "$SERVER_REPO/pom.xml" ] || die "no server repo at $SERVER_REPO (set SERVER_REPO=/path/to/jakarta-mcp-server)"
SERVER_REPO="$(cd "$SERVER_REPO" && pwd)"
COMPOSE=(docker compose -p "$PROJECT" -f "$SERVER_REPO/keycloak/docker-compose.yml" -f "$SERVER_REPO/keycloak/docker-compose.tickets.yml" -f "$ROOT/scripts/docker-compose.nocluster.yml")
if [ "$SERVER" = wildfly ]; then
  OVERRIDE_DIR="$(mktemp -d)"   # not under target/: mvn clean deletes that while the containers still need this file
  cat >"$OVERRIDE_DIR/compose-server.yml" <<YML
services:
  mcp:
    image: $IMAGE
    volumes:
      - $SERVER_REPO/mcp-example-tickets/target/mcp-example-tickets.war:$WAR_PATH:ro
YML
  COMPOSE+=(-f "$OVERRIDE_DIR/compose-server.yml")
fi
NAMES="mcpit-fixture mcpit-example mcpit-tasks mcpit-cdi mcpit-agent mcpit-plain"

stop_all() {
  mkdir -p "$LOGS"
  for name in $NAMES; do
    docker logs "$name" >"$LOGS/$name.log" 2>&1 || true
    docker rm -f "$name" >/dev/null 2>&1 || true
  done
  "${COMPOSE[@]}" logs --no-color >"$LOGS/compose.log" 2>&1 || true
  "${COMPOSE[@]}" down >/dev/null 2>&1 || true
  [ -n "${OVERRIDE_DIR:-}" ] && rm -rf "$OVERRIDE_DIR"
}

cleanup() {
  if [ "$KEEP" = 1 ]; then
    echo; echo "Containers left running (--keep). Stop them with:  scripts/it.sh --stop"
  else
    say "Stopping containers (logs are in $LOGS)"
    stop_all
  fi
}

if [ "$STOP" = 1 ]; then stop_all; exit 0; fi

# --- ports ---------------------------------------------------------------------------------------------------------
if command -v lsof >/dev/null; then
  busy=""
  for port in 8080 8081 8082 8083 8084 8085 8090 8180; do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && busy="$busy $port"
  done
  if [ -n "$busy" ]; then
    echo "These ports are already in use:$busy"
    echo "Probably containers from an earlier run. See what holds them with:  docker ps"
    echo "Stop them with:  docker stop <name>   (or scripts/it.sh --stop for the ones this script started)"
    exit 1
  fi
fi

# --- builds --------------------------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  say "Building the server WARs"
  mvn -q -f "$SERVER_REPO/pom.xml" -DskipTests -DskipITs package -pl mcp-example,mcp-example-tickets,mcp-example-tasks,mcp-it-fixture -am || die "server build failed"
  say "Building this project, the CDI module and the CDI test WAR"
  (cd "$ROOT" && mvn -q -DskipTests -DskipITs install && cd cdi && mvn -q -DskipTests install && cd ../cdi-it && mvn -q package) || die "client build failed"
  if [ "$AGENT" = 1 ]; then
    say "Building the agentic agent WAR"
    (cd "$ROOT/examples/agent-agentic" && mvn -q -DskipTests -DskipITs package) || die "agentic agent build failed"
  fi
  if [ "$PLAIN" = 1 ]; then
    say "Building the plain agent WAR"
    (cd "$ROOT/examples/agent-plain" && mvn -q -DskipTests -DskipITs package) || die "plain agent build failed"
  fi
fi
for war in mcp-it-fixture/target/mcp-it-fixture.war mcp-example/target/mcp-example.war mcp-example-tasks/target/mcp-example-tasks.war mcp-example-tickets/target/mcp-example-tickets.war; do
  [ -f "$SERVER_REPO/$war" ] || die "missing $SERVER_REPO/$war (run without --no-build)"
done
[ -f "$ROOT/cdi-it/target/mcp-client-cdi-it.war" ] || echo "Note: no cdi-it WAR, the CDI check is skipped"
[ "$AGENT" = 1 ] && [ ! -f "$ROOT/examples/agent-agentic/target/mcp-client-agent-agentic.war" ] && { echo "Note: no agentic agent WAR, its checks are skipped"; AGENT=0; }
[ "$PLAIN" = 1 ] && [ ! -f "$ROOT/examples/agent-plain/target/mcp-client-agent-plain.war" ] && { echo "Note: no plain agent WAR, its checks are skipped"; PLAIN=0; }

# --- start ---------------------------------------------------------------------------------------------------------
trap cleanup EXIT
mkdir -p "$LOGS"
rm -f "$LOGS"/*.log

run_server() { # name hostPort war [extra docker args...]
  local name="$1" port="$2" war="$3"; shift 3
  local mounts=(-v "$war:$WAR_PATH")
  if [ "$SERVER" = liberty ]; then
    mounts+=(-v "$SERVER_REPO/mcp-it/src/test/resources/liberty/server.xml:/opt/ol/wlp/usr/servers/defaultServer/server.xml")
  else
    mounts=(-v "$war:$WAR_PATH:ro")
  fi
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" -p "$port:$PORT" --add-host=host.docker.internal:host-gateway "$@" "${mounts[@]}" "$IMAGE" >/dev/null || die "could not start $name"
}

say "Starting containers ($SERVER, $IMAGE)"
run_server mcpit-fixture 8090 "$SERVER_REPO/mcp-it-fixture/target/mcp-it-fixture.war"
run_server mcpit-example 8081 "$SERVER_REPO/mcp-example/target/mcp-example.war"
run_server mcpit-tasks   8082 "$SERVER_REPO/mcp-example-tasks/target/mcp-example-tasks.war" -e MCP_TASKS_ENABLED=true
CDI=0
if [ -f "$ROOT/cdi-it/target/mcp-client-cdi-it.war" ]; then
  run_server mcpit-cdi 8083 "$ROOT/cdi-it/target/mcp-client-cdi-it.war" -e MCP_CLIENT_EXAMPLE_URL=http://host.docker.internal:8081/mcp
  CDI=1
fi
if [ "$AGENT" = 1 ]; then
  run_server mcpit-agent 8084 "$ROOT/examples/agent-agentic/target/mcp-client-agent-agentic.war" -e MCP_CLIENT_EXAMPLE_URL=http://host.docker.internal:8081/mcp -e PAYARA_AGENTIC_LLM_PROVIDER=anthropic -e ANTHROPIC_API_KEY=fake -e PAYARA_AGENTIC_LLM_ANTHROPIC_BASE_URL=http://localhost:$PORT/api/fake-anthropic
fi
if [ "$PLAIN" = 1 ]; then
  run_server mcpit-plain 8085 "$ROOT/examples/agent-plain/target/mcp-client-agent-plain.war" -e MCP_CLIENT_EXAMPLE_URL=http://host.docker.internal:8081/mcp -e AGENT_FAKE_LLM=true
fi
if [ "$KEYCLOAK" = 1 ]; then
  "${COMPOSE[@]}" up -d >"$LOGS/compose-up.log" 2>&1 || { cat "$LOGS/compose-up.log"; die "docker compose failed"; }
fi

wait_for() { # label url expected-codes(space separated)
  local label="$1" url="$2" codes="$3" i code
  printf 'Waiting for %s ' "$label"
  for i in $(seq 1 90); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null || true)"
    case " $codes " in *" $code "*) echo "ok"; return 0 ;; esac
    printf '.'; sleep 2
  done
  echo " TIMEOUT (last status $code). See $LOGS after the run."
  return 1
}

READY=1
wait_for "fixture  (8090)" http://localhost:8090/mcp "400 401 405 406" || READY=0
wait_for "example  (8081)" http://localhost:8081/mcp "400 401 405 406" || READY=0
wait_for "tasks    (8082)" http://localhost:8082/mcp "400 401 405 406" || READY=0
if [ "$KEYCLOAK" = 1 ]; then
  wait_for "keycloak (8180)" http://localhost:8180/realms/mcp/.well-known/openid-configuration "200" || READY=0
  wait_for "tickets  (8080)" http://localhost:8080/.well-known/oauth-protected-resource/mcp "200" || READY=0
fi
CDI_UP=1
if [ "$CDI" = 1 ]; then wait_for "cdi test  (8083)" http://localhost:8083/api/tools "200 500" || CDI_UP=0; fi
AGENT_UP=1
if [ "$AGENT" = 1 ]; then wait_for "agentic agent (8084)" http://localhost:8084/api/ask "405" || AGENT_UP=0; fi
PLAIN_UP=1
if [ "$PLAIN" = 1 ]; then wait_for "plain agent  (8085)" http://localhost:8085/api/ask "405" || PLAIN_UP=0; fi
[ "$READY" = 1 ] || { echo "Not everything came up; the tests would only fail for that reason. Logs: $LOGS"; exit 1; }

# --- tests ---------------------------------------------------------------------------------------------------------
say "Running the tests"
AUTH_ARG=""
[ "$KEYCLOAK" = 1 ] && AUTH_ARG="-Dmcp.auth.url=http://localhost:8080/mcp"
MVN_LOG="$(mktemp)"
(cd "$ROOT" && mvn clean verify $MVN_ARGS -Dmcp.fixture.url=http://localhost:8090/mcp -Dmcp.example.url=http://localhost:8081/mcp -Dmcp.tasks.url=http://localhost:8082/mcp $AUTH_ARG) 2>&1 | tee "$MVN_LOG" | grep -E "Tests run:|FAIL|ERROR|BUILD"
MVN_STATUS=${PIPESTATUS[0]}
mkdir -p "$LOGS" && cp "$MVN_LOG" "$LOGS/maven.log"; rm -f "$MVN_LOG"

CDI_STATUS=0
if [ "$CDI" = 1 ] && [ "$CDI_UP" = 0 ]; then
  say "CDI injection check"
  echo "CDI check: FAILED, the test WAR did not deploy (the reason is in $LOGS/mcpit-cdi.log: grep SEVERE -A4)"
  CDI_STATUS=1
elif [ "$CDI" = 1 ]; then
  say "CDI injection check"
  out="$(curl -s --max-time 20 http://localhost:8083/api/tools || true)"
  echo "$out" | head -20
  if echo "$out" | grep -q "countdown" && echo "$out" | grep -q "same=true"; then echo "CDI check: ok"; else echo "CDI check: FAILED (see $LOGS/mcpit-cdi.log)"; CDI_STATUS=1; fi
fi

check_agent() { # label module port container up
  local label="$1" module="$2" port="$3" name="$4" up="$5" out status=0
  say "$label (scripted model)"
  mkdir -p "$LOGS"
  (cd "$ROOT/examples/$module" && mvn -B verify -Dmcp.example.url=http://localhost:8081/mcp) 2>&1 | tee "$LOGS/$module-maven.log" | grep -E "Tests run:|FAIL|ERROR|BUILD"
  [ "${PIPESTATUS[0]}" = 0 ] || status=1
  if [ "$up" = 0 ]; then
    echo "$label WAR: FAILED, it did not deploy (the reason is in $LOGS/$name.log: grep SEVERE -A4)"
    return 1
  fi
  out="$(curl -s --max-time 30 -X POST -H 'Content-Type: application/json' -d '{"question":"add 2 and 3"}' http://localhost:$port/api/ask || true)"
  echo "$out" | head -20
  if echo "$out" | grep -Eq '"answer":".*5' && [ "$(echo "$out" | grep -o '"name":"add"' | wc -l | tr -d ' ')" = 1 ]; then echo "$label WAR: ok"; else echo "$label WAR: FAILED (see $LOGS/$name.log)"; status=1; fi
  return $status
}
AGENT_STATUS=0
PLAIN_STATUS=0
[ "$AGENT" = 1 ] && { check_agent "Agentic agent" agent-agentic 8084 mcpit-agent "$AGENT_UP" || AGENT_STATUS=1; }
[ "$PLAIN" = 1 ] && { check_agent "Plain agent" agent-plain 8085 mcpit-plain "$PLAIN_UP" || PLAIN_STATUS=1; }

say "Result"
[ "$MVN_STATUS" = 0 ] && echo "Maven: ok" || echo "Maven: FAILED (details in $LOGS/maven.log, reports in target/failsafe-reports)"
[ "$CDI" = 1 ] && { [ "$CDI_STATUS" = 0 ] && echo "CDI:   ok" || echo "CDI:   FAILED"; }
[ "$AGENT" = 1 ] && { [ "$AGENT_STATUS" = 0 ] && echo "Agentic agent: ok" || echo "Agentic agent: FAILED (see $LOGS/agent-agentic-maven.log and $LOGS/mcpit-agent.log)"; }
[ "$PLAIN" = 1 ] && { [ "$PLAIN_STATUS" = 0 ] && echo "Plain agent:   ok" || echo "Plain agent:   FAILED (see $LOGS/agent-plain-maven.log and $LOGS/mcpit-plain.log)"; }
[ "$MVN_STATUS" = 0 ] && [ "$CDI_STATUS" = 0 ] && [ "$AGENT_STATUS" = 0 ] && [ "$PLAIN_STATUS" = 0 ]
