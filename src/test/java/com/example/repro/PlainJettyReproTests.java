package com.example.repro;

import com.example.repro.web.Endpoints;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal reproducer without Spring Boot: plain Jetty 12 (ee11), a bare {@link DispatcherServlet} and
 * {@link ForwardedHeaderFilter}. Requests go through Jetty's in-memory {@link LocalConnector}, so no
 * network port is opened.
 * <p>
 * Asserts the CORRECT behaviour. The async cases with {@code X-Forwarded-Prefix} fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PlainJettyReproTests {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {

        @Bean
        PlainEndpoints endpoints() {
            return new PlainEndpoints();
        }

        // Same catch-all static resource mapping that Spring Boot registers by default.
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
        }

    }

    @RestController
    @RequestMapping("/jetty")
    static class PlainEndpoints extends Endpoints {

    }

    private Server server;

    private LocalConnector connector;

    private WarnLogCapture logs;

    @BeforeAll
    void startJetty() throws Exception {
        this.server = new Server();
        this.connector = new LocalConnector(this.server);
        this.server.addConnector(this.connector);

        ServletContextHandler context = new ServletContextHandler("/");
        AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        spring.register(WebConfig.class);
        ServletHolder dispatcher = new ServletHolder(new DispatcherServlet(spring));
        dispatcher.setAsyncSupported(true);
        dispatcher.setInitOrder(1);
        context.addServlet(dispatcher, "/");

        FilterHolder forwarded = new FilterHolder(new ForwardedHeaderFilter());
        forwarded.setAsyncSupported(true);
        context.addFilter(forwarded, "/*",
                EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));

        this.server.setHandler(context);
        this.server.start();
        this.logs = WarnLogCapture.attach();
    }

    @AfterAll
    void stopJetty() throws Exception {
        if (this.logs != null) {
            this.logs.close();
        }
        if (this.server != null) {
            this.server.stop();
        }
    }

    @ParameterizedTest(name = "/{0} with {1}", quoteTextArguments = false)
    @CsvSource(delimiter = '|', nullValues = "none", textBlock = """
            sync     | none
            sync     | /api
            stream   | none
            stream   | /api
            callable | none
            callable | /api
            deferred | none
            deferred | /api
            """)
    void respondsCorrectlyWithoutWarnings(String style, String prefix) throws Exception {
        this.logs.clear();
        String path = "/jetty/" + style;
        String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + ((prefix != null) ? "X-Forwarded-Prefix: " + prefix + "\r\n" : "") + "\r\n";
        HttpTester.Response response = HttpTester
                .parseResponse(this.connector.getResponse(request, 10, TimeUnit.SECONDS));

        String expectedContextPath = (prefix != null) ? prefix : "";
        assertThat(response.getStatus()).as("HTTP status").isEqualTo(200);
        assertThat(response.getContent()).as("response body")
                .startsWith(style + " from ")
                .contains("requestURI=" + expectedContextPath + path + " contextPath=" + expectedContextPath + "\n");
        assertThat(this.logs.warnings()).as("WARN/ERROR log events").isEmpty();
    }

}
