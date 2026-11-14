package com.example.repro.jetty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Child context served by embedded Jetty. Scans only this package. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan
public class JettyServerConfig {

	@Bean
	JettyServletWebServerFactory jettyServletWebServerFactory(@Value("${app.jetty.port:8081}") int port) {
		return new JettyServletWebServerFactory(port);
	}

}
