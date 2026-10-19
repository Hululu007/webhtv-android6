package com.fongmi.quickjs.utils;

import com.github.catvod.crawler.DebugEvent;
import com.github.catvod.crawler.DebugLogStore;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

public class Async {

    private Promise future;

    private final JSCallFunction success = args -> {
        future.complete(args != null && args.length > 0 ? args[0] : null);
        return null;
    };

    private final JSCallFunction error = args -> {
        Object arg = args != null && args.length > 0 ? args[0] : null;
        String msg = messageOf(arg);
        String stack = stackOf(arg);
        if (DebugLogStore.isEnabled()) {
            DebugLogStore.addEvent(DebugEvent.exception("JSError", msg, stack).reason(DebugEvent.REASON_JS_EXCEPTION));
        }
        // Keep the first JS frame in the exception message: the Java stack alone cannot
        // point at a line inside the spider file.
        future.completeExceptionally(new Exception(stack == null ? msg : msg + " @ " + firstFrame(stack)));
        return null;
    };

    private Async() {
        this.future = new Promise();
    }

    public static Promise run(JSObject object, String name, Object... args) {
        return new Async().call(object, name, args);
    }

    private Promise call(JSObject object, String name, Object... args) {
        JSFunction func = object.getJSFunction(name);
        if (func == null) return missing(name);
        call(func, args);
        return future;
    }

    /**
     * The spider did not export this method. Historically this returned null silently,
     * which surfaced as an empty page with nothing in the log.
     */
    private Promise missing(String name) {
        if (DebugLogStore.isEnabled()) {
            DebugLogStore.addEvent(DebugEvent.exception("MissingMethod", "spider 未导出方法 " + name, null).method(name).reason(DebugEvent.REASON_METHOD_MISSING));
        }
        future.complete(null);
        return future;
    }

    private void call(JSFunction func, Object... args) {
        try {
            Object result = func.call(args);
            if (result instanceof JSObject) then((JSObject) result);
            else future.complete(result);
        } catch (Throwable e) {
            if (DebugLogStore.isEnabled()) {
                DebugLogStore.addEvent(DebugEvent.exception(e.getClass().getSimpleName(), e.getMessage(), stack(e)).reason(DebugEvent.REASON_JS_EXCEPTION));
            }
            future.completeExceptionally(e);
        } finally {
            func.release();
        }
    }

    private void then(JSObject promise) {
        JSFunction then = promise.getJSFunction("then");
        if (then == null) {
            future.complete(promise);
        } else {
            consume(then, success);
            consume(promise.getJSFunction("catch"), error);
        }
    }

    private void consume(JSFunction func, JSCallFunction callback) {
        if (func == null) return;
        try {
            func.call(callback);
        } finally {
            func.release();
        }
    }

    /** A JS {@code Error} stringifies to {@code [object Object]}; prefer its message. */
    private static String messageOf(Object arg) {
        if (arg == null) return "";
        if (arg instanceof JSObject) {
            try {
                String message = ((JSObject) arg).getStringProperty("message");
                if (message != null && !message.isEmpty()) return message;
            } catch (Throwable ignored) {
            }
        }
        return String.valueOf(arg);
    }

    private static String stackOf(Object arg) {
        if (!(arg instanceof JSObject)) return null;
        try {
            String stack = ((JSObject) arg).getStringProperty("stack");
            return stack == null || stack.isEmpty() ? null : stack;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Pulls the first {@code at ...} line out of a JS stack trace. */
    private static String firstFrame(String stack) {
        for (String line : stack.split("\\r?\\n")) {
            String text = line.trim();
            if (text.startsWith("at ")) return text.length() > 200 ? text.substring(0, 200) : text;
        }
        return stack.length() > 200 ? stack.substring(0, 200) : stack;
    }

    private static String stack(Throwable e) {
        if (e == null) return null;
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(writer));
            String text = writer.toString();
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
