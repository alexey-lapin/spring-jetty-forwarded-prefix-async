# Async requests fail on Jetty with `ForwardedHeaderFilter` and `X-Forwarded-Prefix`

With Spring's `ForwardedHeaderFilter` (in Spring Boot: `server.forward-headers-strategy=framework`)
and an `X-Forwarded-Prefix` header, the three async return types below break on Jetty. The same
handlers work on Tomcat.

| Handler return type     | Jetty + `X-Forwarded-Prefix: /api`                                                              |
|-------------------------|-------------------------------------------------------------------------------------------------|
| `StreamingResponseBody` | 200 with the full body, but logs `WARN ... Ignoring exception, response committed already: NoResourceFoundException: No static resource api/jetty/stream` |
| `Callable<T>`           | **404** instead of the value                                                                    |
| `DeferredResult<T>`     | **404** instead of the value                                                                    |
| synchronous             | works                                                                                           |

No forwarded header, or `X-Forwarded-Host` alone, works everywhere.

## Versions

Spring Boot 4.1.2, Spring Framework 7.0.10, Jetty 12.1.14 (ee11), Tomcat 11.0.27, Java 25.

## Run it

```sh
./gradlew test --tests '*ServletAsyncDispatchTests'   # Servlet API only, no Spring: Jetty fails, Tomcat passes
./gradlew test --tests '*PlainJettyReproTests'   # minimal case only: 3 of 8 fail
./gradlew test   # all tests below; 7 of 34 cases fail, all Jetty + X-Forwarded-Prefix + async
./repro.sh       # the Tomcat vs Jetty matrix with curl, printed as a table
```

All tests assert the **correct** behaviour (200, the expected body, no WARN/ERROR log), so the
failing cases are the bug. Every response shows the `requestURI` and `contextPath` the controller saw.
The handlers are shared: [`Endpoints`](src/main/java/com/example/repro/web/Endpoints.java).

1. **Servlet API only, no Spring:** [`ServletAsyncDispatchTests`](src/test/java/com/example/repro/ServletAsyncDispatchTests.java)
   wraps the request so it reports `contextPath=/api`, calls `startAsync(wrapper, response).dispatch()`, and
   prints the path elements the ASYNC dispatch sees. Tomcat: `contextPath=/api servletPath=/app/x`.
   Jetty: `contextPath= servletPath=/api/app/x`. This is the container difference behind the bug.
2. **Minimal, no Spring Boot:** [`PlainJettyReproTests`](src/test/java/com/example/repro/PlainJettyReproTests.java)
   runs plain Jetty 12 with a bare `DispatcherServlet`, `@EnableWebMvc` and `ForwardedHeaderFilter`,
   in memory through Jetty's `LocalConnector`. 3 of 8 cases fail.
3. **Tomcat vs Jetty with Spring Boot:** [`ReproSpringJettyApplicationTests`](src/test/java/com/example/repro/ReproSpringJettyApplicationTests.java)
   starts the Boot app, which runs the same controller on embedded Tomcat (`/tomcat/...`) and embedded
   Jetty (`/jetty/...`) in one JVM, and checks every combination of server, handler type and forwarded
   header. 3 of 24 cases fail, all on Jetty; every Tomcat case passes.

`./repro.sh` output:

```
SERVER  ENDPOINT  HEADER                             STATUS WARN  BODY (requestURI / contextPath seen by the controller)
tomcat  /stream   X-Forwarded-Prefix: /api           200    0     requestURI=/api/tomcat/stream contextPath=/api
tomcat  /callable X-Forwarded-Prefix: /api           200    0     requestURI=/api/tomcat/callable contextPath=/api
tomcat  /deferred X-Forwarded-Prefix: /api           200    0     requestURI=/api/tomcat/deferred contextPath=/api
jetty   /sync     X-Forwarded-Prefix: /api           200    0     requestURI=/api/jetty/sync contextPath=/api
jetty   /stream   X-Forwarded-Prefix: /api           200    1     requestURI=/api/jetty/stream contextPath=/api <-- BUG
jetty   /callable X-Forwarded-Prefix: /api           404    0     (error page) <-- BUG
jetty   /deferred X-Forwarded-Prefix: /api           404    0     (error page) <-- BUG
(all 24 rows are printed; the other 17 are 200 with no WARN)
```

## Cause

An async handler finishes with a second, ASYNC dispatch back into `DispatcherServlet`.

1. `StandardServletAsyncWebRequest.startAsync()` calls `request.startAsync(request, response)` with
   the `ForwardedHeaderFilter` wrapper. That wrapper reports `requestURI=/api/jetty/stream` and
   `contextPath=/api`.
2. Per the `AsyncContext.dispatch()` Javadoc, the ASYNC dispatch goes to that wrapper's `getRequestURI()`.
3. **Jetty** dispatches to `/api/jetty/stream` within its real context path (`/`), and wraps the request
   in its own `AsyncRequest`, which reports `contextPath=""`.
   **Tomcat** strips the wrapper's own context path (`/api`) when choosing the dispatch target and inserts
   its dispatch wrapper *beneath* Spring's wrapper. `DispatcherServlet` sees `requestURI=/api/tomcat/stream`
   and `contextPath=/api`, so Spring MVC resolves `/tomcat/stream`.
4. `ForwardedHeaderFilter` does not re-apply on the ASYNC pass: the Spring wrapper inside Jetty's
   `AsyncRequest` hides the `X-Forwarded-*` headers, so `shouldNotFilter()` returns `true`.
5. `DispatcherServlet` looks up `/api/jetty/stream`, finds no controller, and falls through to the
   static resource handler, which throws `NoResourceFoundException`:
   - `StreamingResponseBody` already wrote and committed the body, so the exception is only logged.
   - `Callable` and `DeferredResult` would write their value during this dispatch; the failed lookup
     prevents that, so the client gets a 404.

The Servlet spec does not say which context path applies when a wrapper's URI includes a prefix
the container does not know about, so this is an incompatibility between Spring's forwarded-header
wrapper and Jetty's async redispatch, not a clear spec violation by either. Spring Boot and the
two-container setup are not involved: `PlainJettyReproTests` reproduces it without them.

## Workarounds

- **Use Tomcat.**
- **Avoid async handlers** on paths that receive `X-Forwarded-Prefix`.
- `server.forward-headers-strategy=native` avoids the failure, but neither Tomcat's nor Jetty's native
  handling applies `X-Forwarded-Prefix`, so the prefix is lost (`contextPath` stays empty). Check with
  `./repro.sh --server.forward-headers-strategy=native`.
