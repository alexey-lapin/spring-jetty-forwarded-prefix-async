package com.example.repro.shared;

import org.springframework.context.annotation.Configuration;

/** Empty parent context; it exists only so the Tomcat and Jetty contexts can run as siblings. */
@Configuration(proxyBeanMethods = false)
public class SharedConfig {

}
