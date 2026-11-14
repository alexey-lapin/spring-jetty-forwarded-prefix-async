package com.example.repro;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects WARN and ERROR events from every logger. Thread-safe, because the interesting events are
 * logged on container threads during the ASYNC dispatch, not on the test thread.
 */
final class WarnLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final Queue<String> warnings = new ConcurrentLinkedQueue<>();

    private WarnLogCapture() {
    }

    static WarnLogCapture attach() {
        WarnLogCapture capture = new WarnLogCapture();
        capture.setContext(rootLogger().getLoggerContext());
        capture.start();
        rootLogger().addAppender(capture);
        return capture;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            this.warnings.add(event.getLoggerName() + ": " + event.getFormattedMessage());
        }
    }

    void clear() {
        this.warnings.clear();
    }

    List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    @Override
    public void close() {
        rootLogger().detachAppender(this);
        stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

}
