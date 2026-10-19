package com.fongmi.quickjs.utils;

import com.fongmi.quickjs.bean.Req;
import com.github.catvod.crawler.DebugEvent;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Connect {

    public static Call to(String url, Req req) {
        OkHttpClient client = OkHttp.client(req.isRedirect(), req.getTimeout());
        return client.newCall(getRequest(url, req, Headers.of(req.getHeader())));
    }

    /**
     * Same as {@code to(url, req).execute()} but records the request in the debug log.
     * Spider requests used to be completely invisible: {@code OkHttp.client()} carries no
     * event listener, and {@code Global.req()} swallowed every exception.
     */
    public static Response execute(String url, Req req) throws IOException {
        DebugEvent event = begin(url, req);
        long start = System.nanoTime();
        try {
            Response res = to(url, req).execute();
            finish(event, res, start);
            return res;
        } catch (IOException e) {
            fail(event, e, start);
            throw e;
        }
    }

    /** Async twin of {@link #execute(String, Req)}. */
    public static void enqueue(String url, Req req, Callback callback) {
        DebugEvent event = begin(url, req);
        long start = System.nanoTime();
        to(url, req).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response res) throws IOException {
                finish(event, res, start);
                callback.onResponse(call, res);
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                fail(event, e, start);
                callback.onFailure(call, e);
            }
        });
    }

    private static DebugEvent begin(String url, Req req) {
        if (!SpiderDebug.isEnabled()) return null;
        DebugEvent event = DebugEvent.http(req.getMethod().toUpperCase(Locale.US), url);
        event.lang = DebugEvent.LANG_JS;
        Map<String, String> header = req.getHeader();
        if (header != null) {
            String referer = value(header, "Referer");
            String agent = value(header, "User-Agent");
            event.extra("referer", referer == null ? "" : cut(referer, 300));
            event.extra("ua", agent == null ? "" : cut(agent, 160));
            event.extra("cookie", value(header, "Cookie") == null ? "0" : "1");
        }
        return event;
    }

    private static void finish(DebugEvent event, Response res, long start) {
        if (event == null) return;
        event.code(res.code()).ms(elapsed(start));
        try {
            long length = res.body() == null ? -1 : res.body().contentLength();
            if (length >= 0) event.bytes(length);
        } catch (Throwable ignored) {
        }
        if (res.code() >= 400) event.reason(DebugEvent.REASON_HTTP_STATUS);
        DebugLogStore.addEvent(event);
    }

    private static void fail(DebugEvent event, IOException e, long start) {
        if (event == null) return;
        event.error = e.getClass().getSimpleName();
        event.msg = e.getMessage();
        event.ms(elapsed(start));
        event.reason(e instanceof SocketTimeoutException ? DebugEvent.REASON_HTTP_TIMEOUT : DebugEvent.REASON_HTTP_ERROR);
        DebugLogStore.addEvent(event);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static String value(Map<String, String> header, String key) {
        for (Map.Entry<String, String> entry : header.entrySet()) if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public static JSObject success(QuickJSContext ctx, Req req, Response res) {
        try (res) {
            JSObject jsObject = ctx.createNewJSObject();
            JSObject jsHeader = ctx.createNewJSObject();
            setHeader(ctx, res, jsHeader);
            jsObject.setProperty("code", res.code());
            jsObject.setProperty("headers", jsHeader);
            if (req.getBuffer() == 0) jsObject.setProperty("content", new String(res.body().bytes(), req.getCharset()));
            if (req.getBuffer() == 1) jsObject.setProperty("content", JSUtil.toArray(ctx, res.body().bytes()));
            if (req.getBuffer() == 2) jsObject.setProperty("content", Util.base64(res.body().bytes()));
            if (req.getBuffer() == 3) jsObject.setProperty("content", res.body().bytes());
            return jsObject;
        } catch (Exception e) {
            return error(ctx);
        }
    }

    public static JSObject error(QuickJSContext ctx) {
        JSObject jsObject = ctx.createNewJSObject();
        JSObject jsHeader = ctx.createNewJSObject();
        jsObject.setProperty("headers", jsHeader);
        jsObject.setProperty("content", "");
        jsObject.setProperty("code", "");
        return jsObject;
    }

    private static Request getRequest(String url, Req req, Headers headers) {
        if (req.getMethod().equalsIgnoreCase("post")) {
            return new Request.Builder().url(url).headers(headers).post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build();
        } else if (req.getMethod().equalsIgnoreCase("header")) {
            return new Request.Builder().url(url).headers(headers).head().build();
        } else {
            return new Request.Builder().url(url).headers(headers).get().build();
        }
    }

    private static RequestBody getPostBody(Req req, String contentType) {
        if (req.getData() != null && "json".equals(req.getPostType())) return getJsonBody(req);
        if (req.getData() != null && "form".equals(req.getPostType())) return getFormBody(req);
        if (req.getData() != null && "form-data".equals(req.getPostType())) return getFormDataBody(req);
        if (req.getBody() != null && contentType != null) return RequestBody.create(req.getBody(), MediaType.get(contentType));
        return RequestBody.create(new byte[0]);
    }

    private static RequestBody getJsonBody(Req req) {
        return RequestBody.create(req.getData().toString(), MediaType.get("application/json; charset=utf-8"));
    }

    private static RequestBody getFormBody(Req req) {
        FormBody.Builder builder = new FormBody.Builder();
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.add(key, params.get(key));
        return builder.build();
    }

    private static RequestBody getFormDataBody(Req req) {
        String boundary = "--dio-boundary-" + new SecureRandom().nextInt(42949) + new SecureRandom().nextInt(67296);
        MultipartBody.Builder builder = new MultipartBody.Builder(boundary).setType(MultipartBody.FORM);
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.addFormDataPart(key, params.get(key));
        return builder.build();
    }

    private static void setHeader(QuickJSContext ctx, Response res, JSObject object) {
        for (Map.Entry<String, List<String>> entry : res.headers().toMultimap().entrySet()) {
            if (entry.getValue().size() == 1) object.setProperty(entry.getKey(), entry.getValue().get(0));
            if (entry.getValue().size() >= 2) object.setProperty(entry.getKey(), JSUtil.toArray(ctx, entry.getValue()));
        }
    }
}
