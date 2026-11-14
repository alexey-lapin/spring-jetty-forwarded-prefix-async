package com.example.repro;

import com.example.repro.jetty.JettyServerConfig;
import com.example.repro.shared.SharedConfig;
import com.example.repro.tomcat.TomcatServerConfig;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the same Spring MVC endpoints on embedded Tomcat and embedded Jetty in one JVM, so their
 * behaviour can be compared side by side:
 *
 * <pre>
 *            parent context (SharedConfig, no web server)
 *       ┌──────┴───────┐
 *   child: Tomcat   child: Jetty
 *   :8080 /tomcat   :8081 /jetty
 * </pre>
 *
 * Each child is an ordinary auto-configured servlet web application with its own web server,
 * DispatcherServlet, ForwardedHeaderFilter and controllers.
 */
public class ReproSpringJettyApplication {

	public static void main(String[] args) {
		run(args);
	}

	/** Starts the parent and both children. The same args are passed to every context. */
	static Running run(String... args) {
		ConfigurableApplicationContext parent = new SpringApplicationBuilder(SharedConfig.class)
			.web(WebApplicationType.NONE)
			.run(args);
		ConfigurableApplicationContext tomcat = new SpringApplicationBuilder(TomcatServerConfig.class).parent(parent)
			.web(WebApplicationType.SERVLET)
			.run(args);
		ConfigurableApplicationContext jetty = new SpringApplicationBuilder(JettyServerConfig.class).parent(parent)
			.web(WebApplicationType.SERVLET)
			.run(args);
		return new Running(parent, tomcat, jetty);
	}

	/** Handles to the running contexts. Closing it closes the parent, which also closes both children. */
	record Running(ConfigurableApplicationContext parent, ConfigurableApplicationContext tomcat,
			ConfigurableApplicationContext jetty) implements AutoCloseable {

		int tomcatPort() {
			return ((WebServerApplicationContext) this.tomcat).getWebServer().getPort();
		}

		int jettyPort() {
			return ((WebServerApplicationContext) this.jetty).getWebServer().getPort();
		}

		@Override
		public void close() {
			this.parent.close();
		}

	}

}
