package com.example.repro.tomcat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Child context served by embedded Tomcat. Scans only this package. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan
public class TomcatServerConfig {

	// Declaring the factory explicitly makes the server auto-config back off,
	// so this context gets Tomcat even though Jetty is also on the classpath.
	@Bean
	TomcatServletWebServerFactory tomcatServletWebServerFactory(@Value("${app.tomcat.port:8080}") int port) {
		return new TomcatServletWebServerFactory(port);
	}

}
