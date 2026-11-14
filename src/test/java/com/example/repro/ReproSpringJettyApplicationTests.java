package com.example.repro;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every combination of container, handler style and forwarded header, asserting the CORRECT behaviour.
 * <p>
 * On Spring Framework 7.0.10 / Jetty 12.1.14 the three async styles fail on Jetty with
 * {@code X-Forwarded-Prefix}; all other cases pass.
 * <p>
 * Requests run one at a time, so WARN events can be attributed to the request that caused them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReproSpringJettyApplicationTests {

    enum Server {

        JETTY,
        TOMCAT,
        ;

        String path() {
            return "/" + name().toLowerCase();
        }

    }

    enum Header {

        NONE(null, null),
        PREFIX("X-Forwarded-Prefix", "/api"),
        HOST("X-Forwarded-Host", "public.example"),
        ;

        final String name;

        final String value;

        Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return (this.name != null) ? this.name + ": " + this.value : "no forwarded header";
        }

    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ReproSpringJettyApplication.Running app;

    private WarnLogCapture logs;

    @BeforeAll
    void startBothContainers() {
        // Port 0: each server binds a free port itself, read back from the running server.
        this.app = ReproSpringJettyApplication.run("--app.tomcat.port=0", "--app.jetty.port=0");
        this.logs = WarnLogCapture.attach();
    }

    @AfterAll
    void stop() {
        if (this.logs != null) {
            this.logs.close();
        }
        if (this.app != null) {
            this.app.close(); // closes the parent, which also closes both child contexts
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(Server.values())
                .flatMap((server) -> Stream.of("sync", "stream", "callable", "deferred")
                        .flatMap((style) -> Stream.of(Header.values()).map((header) -> Arguments.of(server, style, header))));
    }

    @ParameterizedTest(name = "{0} /{1} with {2}", quoteTextArguments = false)
    @MethodSource("cases")
    void respondsCorrectlyWithoutWarnings(Server server, String style, Header header) throws Exception {
        this.logs.clear();
        String path = server.path() + "/" + style;
        HttpResponse<String> response = get(server, path, header);

        String expectedContextPath = (header == Header.PREFIX) ? header.value : "";
        assertThat(response.statusCode()).as("HTTP status").isEqualTo(200);
        assertThat(response.body()).as("response body")
                .startsWith(style + " from ")
                .contains("requestURI=" + expectedContextPath + path + " contextPath=" + expectedContextPath + "\n");
        assertThat(this.logs.warnings()).as("WARN/ERROR log events").isEmpty();
    }

    private HttpResponse<String> get(Server server, String path, Header header) throws Exception {
        int port = (server == Server.TOMCAT) ? this.app.tomcatPort() : this.app.jettyPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (header.name != null) {
            request.header(header.name, header.value);
        }
        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
