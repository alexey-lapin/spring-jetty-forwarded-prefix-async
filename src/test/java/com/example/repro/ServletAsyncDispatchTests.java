package com.example.repro;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet API only, no Spring. A filter wraps the request so that it reports an extra context path
 * prefix ({@code contextPath=/api}, {@code requestURI=/api/app/x}), the way a forwarded-prefix filter does.
 * The servlet calls {@code startAsync(wrapper, response).dispatch()} and, on the ASYNC dispatch, writes
 * the path elements it sees.
 * <p>
 * Tomcat strips the wrapper's context path and dispatches to {@code /app/x}. Jetty strips only the real
 * context path ({@code /}) and dispatches to {@code /api/app/x}. The assertions expect Tomcat's result.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ServletAsyncDispatchTests {

    static final String PREFIX = "/api";

    enum Container {
        JETTY, TOMCAT
    }

    /** Reports an extra context path prefix the container does not know about. */
    static class PrefixFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {

                @Override
                public String getContextPath() {
                    return PREFIX + super.getContextPath();
                }

                @Override
                public String getRequestURI() {
                    return PREFIX + super.getRequestURI();
                }

                @Override
                public StringBuffer getRequestURL() {
                    StringBuffer url = super.getRequestURL();
                    int pathStart = url.indexOf("/", url.indexOf("://") + 3);
                    return url.insert(pathStart, PREFIX);
                }

            }, response);
        }

    }

    /** Starts async with the wrapped request, dispatches, and reports what the ASYNC dispatch sees. */
    static class ProbeServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                AsyncContext async = request.startAsync(request, response);
                async.dispatch();
                return;
            }
            response.setContentType("text/plain");
            response.getWriter().print("dispatcherType=" + request.getDispatcherType()
                    + " requestURI=" + request.getRequestURI()
                    + " contextPath=" + request.getContextPath()
                    + " servletPath=" + request.getServletPath()
                    + " pathInfo=" + request.getPathInfo());
        }

    }

    @TempDir
    Path tomcatBaseDir;

    @ParameterizedTest
    @EnumSource
    void asyncDispatchTargetsPathWithinWrapperContextPath(Container container) throws Exception {
        String body = switch (container) {
            case JETTY -> requestOnJetty();
            case TOMCAT -> requestOnTomcat();
        };
        System.out.println(container + ": " + body);

        assertThat(body).isEqualTo("status=200 dispatcherType=ASYNC requestURI=/api/app/x contextPath=/api"
                + " servletPath=/app/x pathInfo=null");
    }

    private String requestOnJetty() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");
        ServletHolder servlet = new ServletHolder(new ProbeServlet());
        servlet.setAsyncSupported(true);
        context.addServlet(servlet, "/");
        FilterHolder filter = new FilterHolder(new PrefixFilter());
        filter.setAsyncSupported(true);
        context.addFilter(filter, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(context);

        server.start();
        try {
            return get(connector.getLocalPort());
        }
        finally {
            server.stop();
        }
    }

    private String requestOnTomcat() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(this.tomcatBaseDir.toString());
        tomcat.setHostname("127.0.0.1");
        tomcat.setPort(0);

        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "probe", new ProbeServlet()).setAsyncSupported(true);
        context.addServletMappingDecoded("/", "probe");
        FilterDef filterDef = new FilterDef();
        filterDef.setFilterName("prefix");
        filterDef.setFilter(new PrefixFilter());
        filterDef.setAsyncSupported("true");
        context.addFilterDef(filterDef);
        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName("prefix");
        filterMap.addURLPattern("/*");
        filterMap.setDispatcher(DispatcherType.REQUEST.name());
        context.addFilterMap(filterMap);

        tomcat.getConnector().setProperty("address", "127.0.0.1");
        tomcat.start();
        try {
            return get(tomcat.getConnector().getLocalPort());
        }
        finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static String get(int port) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app/x"))
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return "status=" + response.statusCode() + " " + response.body();
        }
    }

}
