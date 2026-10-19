package com.github.catvod.crawler;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Correlates every event produced by one user action (one category page, one search,
 * one detail open, one playback resolve).
 *
 * <p>The id is short on purpose: it ends up in every log line and in the card header.
 * Four hex digits wrap after 65536 actions, which is far beyond the retained window.
 *
 * <p>Threading: the value is a {@link ThreadLocal}, so it does NOT follow work into the
 * JS/Python executor by itself. Callers that hand work to another thread must carry the
 * context explicitly — see {@link #wrap(String, String, Callable)}.
 */
public class DebugTrace {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    public static String begin() {
        String trace = next();
        TRACE.set(trace);
        return trace;
    }

    public static String current() {
        return TRACE.get();
    }

    public static void set(String trace) {
        if (trace == null || trace.isEmpty()) TRACE.remove();
        else TRACE.set(trace);
    }

    public static void clear() {
        TRACE.remove();
    }

    public static String next() {
        return String.format("%04x", SEQ.incrementAndGet() & 0xffff);
    }

    /**
     * Restores {@code trace} and {@code site} on the calling thread for the duration of
     * {@code callable}. Used when a spider method is dispatched onto its own executor,
     * where the {@link ThreadLocal} values set by the caller are not visible.
     */
    public static <T> Callable<T> wrap(String trace, String site, Callable<T> callable) {
        return () -> {
            String previousTrace = current();
            String previousSite = SpiderDebug.getSpider();
            set(trace);
            SpiderDebug.setSpider(site);
            try {
                return callable.call();
            } finally {
                set(previousTrace);
                if (previousSite == null) SpiderDebug.clearSpider();
                else SpiderDebug.setSpider(previousSite);
            }
        };
    }
}
