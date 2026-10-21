package com.github.catvod.crawler;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only debug log shared by the JS engine, the Python bridge, catvod and the app.
 *
 * <p>Two representations are kept in memory:
 * <ul>
 *   <li>{@code EVENTS} — structured {@link DebugEvent}s, used by the card viewer.</li>
 *   <li>{@code LINES} — the rendered text form, used by the legacy text viewer, by
 *       {@code text()}, and by everything that predates structured logging.</li>
 * </ul>
 * They are kept one-to-one so {@code size()} and {@code snapshot()} keep their meaning.
 *
 * <p>On disk each line is a JSON object that also carries a {@code line} field with the
 * rendered text. That way restoring never needs to render, and a build that predates
 * structured logging can still read the file.
 */
public class DebugLogStore {

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final ArrayDeque<DebugEvent> EVENTS = new ArrayDeque<>();
    private static final ThreadLocal<SimpleDateFormat> FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));
    private static final String FILE_NAME = "webhtv-debug-log.txt";
    private static final String PREF_ENABLED = "debug_log";
    private static final int MAX_MESSAGE_CHARS = 12000;
    /**
     * Kept deliberately small. Each HTTP event can now carry up to 8 KB of request body and
     * 4 KB of response body, so the old 20000 cap could hold tens of megabytes in a process
     * whose whole heap is 512 MB on the TV. 4000 events still covers dozens of operations.
     */
    private static final int MAX_EVENTS = 4000;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final int COMPACT_CHECK_EVERY = 200;
    private static long version;
    private static volatile boolean enabled;
    private static int writes;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        DebugLogStore.enabled = enabled;
        Prefers.put(PREF_ENABLED, enabled);
        if (enabled) add("debug", "调试日志已开启");
        else clear();
    }

    public static void restoreEnabled() {
        enabled = Prefers.getBoolean(PREF_ENABLED);
        if (!enabled) return;
        synchronized (LOCK) {
            loadLocked();
        }
        add("debug", "调试日志已恢复");
    }

    /** Legacy entry point. Kept byte-for-byte compatible so existing call sites stay valid. */
    public static void add(String tag, String msg) {
        if (!isEnabled()) return;
        if (TextUtils.isEmpty(msg)) return;
        String safeTag = safe(tag);
        String text = limit(msg);
        DebugEvent event = new DebugEvent();
        event.kind = DebugEvent.KIND_LEGACY;
        event.method = safeTag;
        event.msg = text;
        event.trace = DebugTrace.current();
        event.site = SpiderDebug.getSpider();
        appendLocked(event, safeTag, text);
    }

    /** Structured entry point. Renders {@code tag: [trace] summary} into the text view. */
    public static void addEvent(DebugEvent event) {
        if (!isEnabled()) return;
        if (event == null) return;
        if (event.ts == null) event.stamp();
        if (event.trace == null) event.trace = DebugTrace.current();
        if (event.site == null) event.site = SpiderDebug.getSpider();
        String tag = tagOf(event);
        String text = render(event);
        appendLocked(event, tag, text);
    }

    private static void appendLocked(DebugEvent event, String tag, String text) {
        String line = FORMAT.get().format(new Date(event.epoch)) + " [" + Thread.currentThread().getName() + "] " + safe(tag) + ": " + limit(text);
        synchronized (LOCK) {
            LINES.addLast(line);
            EVENTS.addLast(event);
            while (LINES.size() > MAX_EVENTS) LINES.removeFirst();
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
            version++;
            writeLocked(event, line);
        }
    }

    private static String tagOf(DebugEvent event) {
        if (event == null || event.kind == null) return "Debug";
        switch (event.kind) {
            case DebugEvent.KIND_CALL:
                return "call";
            case DebugEvent.KIND_HTTP:
                return "http";
            case DebugEvent.KIND_CONSOLE:
                return "console";
            case DebugEvent.KIND_EXCEPTION:
                return "spider-error";
            case DebugEvent.KIND_RESULT:
                return "result";
            case DebugEvent.KIND_STAGE:
                return "stage";
            default:
                return TextUtils.isEmpty(event.method) ? "Debug" : event.method;
        }
    }

    private static String render(DebugEvent event) {
        String summary = event.summary();
        if (TextUtils.isEmpty(summary)) summary = event.kind;
        // Same marker the legacy path emits, so {@link #snapshot(String)} keeps working:
        // it filters the text view on "[spider=xxx] ".
        String site = TextUtils.isEmpty(event.site) ? "" : "[spider=" + event.site + "] ";
        String prefix = TextUtils.isEmpty(event.trace) ? "" : "[" + event.trace + "] ";
        if (event.failed()) {
            String reason = TextUtils.isEmpty(event.reason) ? "失败" : event.reason;
            return site + prefix + "✗ " + reason + " " + summary;
        }
        return site + prefix + summary;
    }

    public static String text() {
        if (!isEnabled()) return "调试日志未开启";
        List<String> copy;
        synchronized (LOCK) {
            if (LINES.isEmpty()) loadLocked();
            copy = new ArrayList<>(LINES);
        }
        if (copy.isEmpty()) return "暂无调试日志";
        StringBuilder builder = new StringBuilder();
        for (String line : copy) builder.append(line).append('\n');
        return builder.toString();
    }

    public static String text(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return text();
        if (!isEnabled()) return "调试日志未开启";
        List<String> copy = snapshot(siteKey);
        if (copy.isEmpty()) return "暂无该 Spider 的调试日志";
        StringBuilder builder = new StringBuilder();
        for (String line : copy) builder.append(line).append('\n');
        return builder.toString();
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static List<String> snapshot(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return snapshot();
        synchronized (LOCK) {
            if (enabled && LINES.isEmpty()) loadLocked();
            String marker = "[spider=" + siteKey + "] ";
            List<String> result = new ArrayList<>();
            for (String line : LINES) if (line.contains(marker)) result.add(line);
            return result;
        }
    }

    /** Structured events, newest last. Used by the card viewer. */
    public static List<DebugEvent> events() {
        synchronized (LOCK) {
            if (enabled && EVENTS.isEmpty() && !LINES.isEmpty()) loadLocked();
            return new ArrayList<>(EVENTS);
        }
    }

    public static List<DebugEvent> events(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return events();
        List<DebugEvent> all = events();
        List<DebugEvent> result = new ArrayList<>();
        for (DebugEvent event : all) if (siteKey.equals(event.site)) result.add(event);
        return result;
    }

    public static int size(String siteKey) {
        return snapshot(siteKey).size();
    }

    public static int size() {
        synchronized (LOCK) {
            if (enabled && LINES.isEmpty()) loadLocked();
            return LINES.size();
        }
    }

    public static long bytes() {
        try {
            File file = file();
            return file != null && file.exists() ? file.length() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static long version() {
        return version;
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            EVENTS.clear();
            version++;
            delete();
        }
    }

    private static String safe(String tag) {
        return TextUtils.isEmpty(tag) ? "Debug" : tag;
    }

    private static String limit(String msg) {
        if (msg.length() <= MAX_MESSAGE_CHARS) return msg;
        return msg.substring(0, MAX_MESSAGE_CHARS) + " ...(truncated " + (msg.length() - MAX_MESSAGE_CHARS) + " chars)";
    }

    private static File file() {
        try {
            return new File(Init.context().getCacheDir(), FILE_NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeLocked(DebugEvent event, String line) {
        try {
            File file = file();
            if (file == null) return;
            String json = event.toJson();
            String record = json.substring(0, json.length() - 1) + ",\"line\":\"" + DebugEvent.escape(line) + "\"}\n";
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write(record.getBytes(StandardCharsets.UTF_8));
            }
            if (++writes % COMPACT_CHECK_EVERY == 0 && file.length() > MAX_FILE_BYTES) compactLocked(file);
        } catch (Throwable ignored) {
        }
    }

    /** Rewrites the file from memory when it grows past {@link #MAX_FILE_BYTES}. */
    private static void compactLocked(File file) {
        try {
            StringBuilder builder = new StringBuilder();
            int skip = Math.max(0, EVENTS.size() - MAX_EVENTS);
            int index = 0;
            synchronized (LOCK) {
                for (DebugEvent event : EVENTS) {
                    if (index++ < skip) continue;
                    String json = event.toJson();
                    builder.append(json, 0, json.length() - 1).append(",\"line\":\"").append(DebugEvent.escape(render(event))).append("\"}\n");
                }
            }
            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadLocked() {
        try {
            File file = file();
            if (file == null || !file.exists()) return;
            String text = readAll(file);
            if (TextUtils.isEmpty(text)) return;
            LINES.clear();
            EVENTS.clear();
            for (String line : text.split("\\r?\\n")) {
                if (TextUtils.isEmpty(line)) continue;
                if (line.charAt(0) == '{') {
                    DebugEvent event = DebugEvent.fromJson(line);
                    String rendered = str(line, "line");
                    if (event != null) {
                        EVENTS.addLast(event);
                        LINES.addLast(rendered != null ? rendered : line);
                        continue;
                    }
                }
                LINES.addLast(line);
            }
        } catch (Throwable e) {
        }
    }

    private static String str(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                if (next == 'n') builder.append('\n');
                else if (next == 'r') builder.append('\r');
                else if (next == 't') builder.append('\t');
                else builder.append(next);
            } else if (c == '"') break;
            else builder.append(c);
        }
        return builder.toString();
    }

    private static String readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void delete() {
        try {
            File file = file();
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }
}
