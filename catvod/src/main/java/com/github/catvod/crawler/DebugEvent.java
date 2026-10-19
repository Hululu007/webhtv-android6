package com.github.catvod.crawler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One structured debug event.
 *
 * <p>This is the unit that {@link DebugLogStore} persists and that the in-app log viewer
 * aggregates into per-trace cards. It is deliberately dependency-free (no gson, no
 * org.json) because it sits in catvod and is shared by the JS engine, the Python bridge
 * and the app module.
 *
 * <p>Serialisation is JSON Lines: one flat object per line. The {@code line} field holds
 * the pre-rendered text form so that:
 * <ul>
 *   <li>the legacy text viewer keeps working without knowing about events,</li>
 *   <li>restoring from disk never needs a JSON parser to render.</li>
 * </ul>
 */
public class DebugEvent {

    public static final String KIND_CALL = "call";
    public static final String KIND_HTTP = "http";
    public static final String KIND_CONSOLE = "console";
    public static final String KIND_EXCEPTION = "exception";
    public static final String KIND_RESULT = "result";
    public static final String KIND_STAGE = "stage";
    public static final String KIND_LEGACY = "legacy";

    public static final String LANG_JS = "js";
    public static final String LANG_PY = "py";
    public static final String LANG_JAR = "jar";
    public static final String LANG_APP = "app";

    /** Standardised failure reasons. Keep in sync with the viewer's suggestion table. */
    public static final String REASON_HTTP_TIMEOUT = "http-timeout";
    public static final String REASON_HTTP_STATUS = "http-status";
    public static final String REASON_HTTP_ERROR = "http-error";
    public static final String REASON_JS_EXCEPTION = "js-exception";
    public static final String REASON_METHOD_MISSING = "method-missing";
    public static final String REASON_EMPTY_RESULT = "empty-result";
    public static final String REASON_SELECTOR_MISS = "selector-miss";
    public static final String REASON_PARSE_TIMEOUT = "parse-timeout";
    public static final String REASON_PARSE_ERROR = "parse-error";
    public static final String REASON_EMPTY_URL = "empty-url";

    private static final ThreadLocal<SimpleDateFormat> FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    public long epoch;
    public String ts;
    public String trace;
    public String site;
    public String lang;
    public String kind;
    public String method;
    public String url;
    public Integer code;
    public Long ms;
    public Long bytes;
    public String error;
    public String msg;
    public String stack;
    public String reason;
    public Integer items;
    public Boolean ok;
    public String extra;

    public DebugEvent() {
        stamp();
    }

    public DebugEvent stamp() {
        this.epoch = System.currentTimeMillis();
        this.ts = FORMAT.get().format(new Date(epoch));
        return this;
    }

    public static DebugEvent of(String kind) {
        DebugEvent event = new DebugEvent();
        event.kind = kind;
        event.trace = DebugTrace.current();
        event.site = SpiderDebug.getSpider();
        return event;
    }

    public static DebugEvent call(String method, String args) {
        DebugEvent event = of(KIND_CALL).method(method);
        event.msg = args == null || args.isEmpty() ? method : method + " " + args;
        return event;
    }

    public static DebugEvent http(String method, String url) {
        DebugEvent event = of(KIND_HTTP).method(method);
        event.url = url;
        return event;
    }

    public static DebugEvent console(String level, String text) {
        DebugEvent event = of(KIND_CONSOLE);
        event.method = level;
        event.msg = text;
        return event;
    }

    public static DebugEvent exception(String error, String message, String stack) {
        DebugEvent event = of(KIND_EXCEPTION);
        event.error = error;
        event.msg = message;
        event.stack = stack;
        event.ok = false;
        return event;
    }

    public static DebugEvent result(boolean ok, int items, long ms) {
        DebugEvent event = of(KIND_RESULT);
        event.ok = ok;
        event.items = items;
        event.ms = ms;
        return event;
    }

    public static DebugEvent stage(String name) {
        DebugEvent event = of(KIND_STAGE);
        event.method = name;
        return event;
    }

    public DebugEvent method(String method) {
        this.method = method;
        return this;
    }

    public DebugEvent lang(String lang) {
        this.lang = lang;
        return this;
    }

    public DebugEvent site(String site) {
        this.site = site;
        return this;
    }

    public DebugEvent trace(String trace) {
        this.trace = trace;
        return this;
    }

    public DebugEvent ms(long ms) {
        this.ms = ms;
        return this;
    }

    public DebugEvent code(int code) {
        this.code = code;
        return this;
    }

    public DebugEvent bytes(long bytes) {
        this.bytes = bytes;
        return this;
    }

    public DebugEvent reason(String reason) {
        this.reason = reason;
        return this;
    }

    public DebugEvent extra(String key, String value) {
        if (key == null || value == null) return this;
        String item = "\"" + escape(key) + "\":\"" + escape(value) + "\"";
        this.extra = this.extra == null || this.extra.isEmpty() ? item : this.extra + "," + item;
        return this;
    }

    public boolean failed() {
        if (Boolean.FALSE.equals(ok)) return true;
        return error != null || (reason != null && !reason.isEmpty());
    }

    /** Human readable one-liner, used by the text viewer and by {@code line}. */
    public String summary() {
        if (msg != null && !msg.isEmpty()) return msg;
        StringBuilder builder = new StringBuilder();
        if (method != null) builder.append(method).append(' ');
        if (url != null) builder.append(url).append(' ');
        if (code != null) builder.append(code).append(' ');
        if (ms != null) builder.append(ms).append("ms ");
        if (bytes != null) builder.append(bytes).append('B');
        return builder.toString().trim();
    }

    public String toJson() {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{');
        append(builder, "ts", ts);
        append(builder, "ep", epoch);
        append(builder, "trace", trace);
        append(builder, "site", site);
        append(builder, "lang", lang);
        append(builder, "kind", kind);
        append(builder, "method", method);
        append(builder, "url", url);
        append(builder, "code", code);
        append(builder, "ms", ms);
        append(builder, "bytes", bytes);
        append(builder, "error", error);
        append(builder, "msg", msg);
        append(builder, "stack", stack);
        append(builder, "reason", reason);
        append(builder, "items", items);
        append(builder, "ok", ok);
        appendRaw(builder, "extra", extra == null || extra.isEmpty() ? null : "{" + extra + "}");
        builder.append('}');
        return builder.toString();
    }

    /**
     * Minimal flat-object parser. Only used to restore events written by {@link #toJson()},
     * so it does not need to handle nesting beyond the known {@code extra} object.
     */
    public static DebugEvent fromJson(String json) {
        if (json == null) return null;
        String text = json.trim();
        if (text.isEmpty() || text.charAt(0) != '{') return null;
        try {
            DebugEvent event = new DebugEvent();
            // `extra` holds keys like url/error/referer, which the flat scanner below would
            // otherwise mistake for top-level fields. Cut it out before scanning.
            event.extra = rawObject(text, "extra");
            String body = stripObject(text, "extra");
            event.ts = str(body, "ts");
            event.epoch = num(body, "ep");
            event.trace = str(body, "trace");
            event.site = str(body, "site");
            event.lang = str(body, "lang");
            event.kind = str(body, "kind");
            event.method = str(body, "method");
            event.url = str(body, "url");
            event.code = intOf(body, "code");
            event.ms = integer(body, "ms");
            event.bytes = integer(body, "bytes");
            event.error = str(body, "error");
            event.msg = str(body, "msg");
            event.stack = str(body, "stack");
            event.reason = str(body, "reason");
            event.items = intOf(body, "items");
            event.ok = bool(body, "ok");
            return event;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void append(StringBuilder builder, String key, Object value) {
        if (value == null) return;
        if (builder.charAt(builder.length() - 1) != '{') builder.append(',');
        builder.append('"').append(key).append("\":");
        if (value instanceof Number || value instanceof Boolean) builder.append(value);
        else builder.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static void appendRaw(StringBuilder builder, String key, String raw) {
        if (raw == null) return;
        if (builder.charAt(builder.length() - 1) != '{') builder.append(',');
        builder.append('"').append(key).append("\":").append(raw);
    }

    public static String escape(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) builder.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else builder.append(c);
            }
        }
        return builder.toString();
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
                else if (next == 'u' && i + 4 < json.length()) {
                    builder.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                } else builder.append(next);
            } else if (c == '"') break;
            else builder.append(c);
        }
        return builder.toString();
    }

    private static Long integer(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
        if (end == start) return null;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (Throwable e) {
            return null;
        }
    }

    private static long num(String json, String key) {
        Long value = integer(json, key);
        return value == null ? 0 : value;
    }

    private static Integer intOf(String json, String key) {
        Long value = integer(json, key);
        return value == null ? null : value.intValue();
    }

    private static Boolean bool(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        if (json.startsWith("true", start)) return Boolean.TRUE;
        if (json.startsWith("false", start)) return Boolean.FALSE;
        return null;
    }

    /** Returns {@code json} with the {@code "key":{...}} member removed. */
    private static String stripObject(String json, String key) {
        String marker = "\"" + key + "\":{";
        int start = json.indexOf(marker);
        if (start < 0) return json;
        int depth = 1;
        for (int i = start + marker.length(); i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(0, start) + json.substring(i + 1);
        }
        return json;
    }

    private static String rawObject(String json, String key) {
        String marker = "\"" + key + "\":{";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int depth = 1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(start, i);
        }
        return null;
    }
}
