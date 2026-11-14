#!/usr/bin/env bash
# Starts the app (Tomcat on :8080, Jetty on :8081), requests every endpoint with and without
# forwarded headers, and prints a table. Extra arguments go to the app, for example:
#   ./repro.sh --server.forward-headers-strategy=native
set -euo pipefail
cd "$(dirname "$0")"

TOMCAT_PORT=8080
JETTY_PORT=8081
LOG=build/repro.log

fail() { echo "ERROR: $*" >&2; exit 1; }

# Refuse to run if something else already answers on our ports: its responses would be mixed into the table.
for port in $TOMCAT_PORT $JETTY_PORT; do
  if curl -s -o /dev/null --max-time 2 "localhost:$port"; then
    fail "port $port is already in use; stop whatever is listening there and retry"
  fi
done

./gradlew -q bootJar
java -jar build/libs/repro-spring-jetty-0.0.1-SNAPSHOT.jar \
  --app.tomcat.port=$TOMCAT_PORT --app.jetty.port=$JETTY_PORT "$@" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; wait $APP_PID 2>/dev/null || true' EXIT

# Wait (max 60s) until THIS process logs that both servers started.
ready=false
for _ in $(seq 1 120); do
  kill -0 $APP_PID 2>/dev/null || { cat "$LOG" >&2; fail "the app exited during startup (log above)"; }
  if grep -q "Tomcat started on port $TOMCAT_PORT" "$LOG" && grep -q "Jetty started on port $JETTY_PORT" "$LOG"; then
    ready=true
    break
  fi
  sleep 0.5
done
$ready || { tail -n 50 "$LOG" >&2; fail "the app did not start both servers within 60s (log tail above)"; }

warn_count() { grep -c 'response committed already' "$LOG" || true; }

printf '%-7s %-9s %-34s %-6s %-5s %s\n' SERVER ENDPOINT HEADER STATUS WARN 'BODY (requestURI / contextPath seen by the controller)'
for server in tomcat jetty; do
  port=$([ $server = tomcat ] && echo $TOMCAT_PORT || echo $JETTY_PORT)
  for style in sync stream callable deferred; do
    for header in '' 'X-Forwarded-Prefix: /api' 'X-Forwarded-Host: public.example'; do
      before=$(warn_count)
      args=(-s --max-time 10 -o build/repro-body.txt -w '%{http_code}')
      [ -n "$header" ] && args+=(-H "$header")
      status=$(curl "${args[@]}" "localhost:$port/$server/$style")
      sleep 0.2 # let any log line from the ASYNC dispatch land
      warns=$(( $(warn_count) - before ))
      if [ "$status" = 200 ]; then
        body=$(sed -E 's/.* (requestURI=.*)/\1/' build/repro-body.txt | head -1)
      else
        body='(error page)'
      fi
      flag=$([ "$status" != 200 ] || [ "$warns" -gt 0 ] && echo ' <-- BUG' || true)
      printf '%-7s %-9s %-34s %-6s %-5s %s%s\n' "$server" "/$style" "${header:-(none)}" "$status" "$warns" "$body" "$flag"
    done
  done
done

echo
echo "WARN lines in $LOG:"
grep 'response committed already' "$LOG" | sed -E 's/^.* (WARN) .* : /\1 DefaultHandlerExceptionResolver: /' || echo '(none)'
