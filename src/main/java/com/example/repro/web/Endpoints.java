package com.example.repro.web;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The same four endpoints, mounted under {@code /tomcat} and {@code /jetty} by the subclasses.
 * <p>
 * Each response says which handler style produced it, which container served it, and the request URI
 * and context path seen by the controller. With {@code X-Forwarded-Prefix: /api}, the last two should be
 * {@code /api/...} and {@code /api}.
 */
public abstract class Endpoints {

	/** Plain synchronous handler: no async dispatch, always works. */
	@GetMapping("/sync")
	String sync(HttpServletRequest request) {
		return describe("sync", request);
	}

	/** Body is written on an async thread, then the container performs an ASYNC dispatch. */
	@GetMapping("/stream")
	StreamingResponseBody stream(HttpServletRequest request) {
		String body = describe("stream", request);
		return (out) -> {
			out.write(body.getBytes(StandardCharsets.UTF_8));
			out.flush();
		};
	}

	/** Value is produced on an async thread and written during the ASYNC dispatch. */
	@GetMapping("/callable")
	Callable<String> callable(HttpServletRequest request) {
		String body = describe("callable", request);
		return () -> body;
	}

	/** Value is set from another thread and written during the ASYNC dispatch. */
	@GetMapping("/deferred")
	DeferredResult<String> deferred(HttpServletRequest request) {
		String body = describe("deferred", request);
		DeferredResult<String> result = new DeferredResult<>();
		CompletableFuture.runAsync(() -> result.setResult(body));
		return result;
	}

	private static String describe(String style, HttpServletRequest request) {
		return style + " from " + request.getServletContext().getServerInfo() + " requestURI="
				+ request.getRequestURI() + " contextPath=" + request.getContextPath() + "\n";
	}

}
