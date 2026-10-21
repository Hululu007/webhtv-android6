package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import com.github.catvod.crawler.DebugEvent;
import com.github.catvod.crawler.DebugLogStore;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Records one structured event per HTTP call: full URL, every request header, the request
 * body, the status line and a preview of the response body.
 *
 * <p>Mounted on {@code OkHttp.client()} only. That single client is what every JAR spider
 * reaches through {@code com.github.catvod.net.OkHttp}, so one hook covers all of them.
 * {@code OkHttp.player()} keeps its own {@code DebugEventListener} and is left untouched.
 *
 * <p>Two hard limits keep the log usable:
 * <ul>
 *   <li>request bodies over {@link #MAX_REQ_BODY} are not read at all (a streaming upload
 *       would otherwise be buffered into memory);</li>
 *   <li>binary responses (video/audio/image) are never previewed, only measured.</li>
 * </ul>
 *
 * <p>Values are stored verbatim. Redaction belongs to the export path, not to capture:
 * the whole point of this log is to let the user see what actually went over the wire.
 */
public class TraceInterceptor implements Interceptor {

    private static final int MAX_REQ_BODY = 8 * 1024;
    private static final int MAX_RES_BODY = 4 * 1024;
    private static final int MAX_HEADERS = 60;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!DebugLogStore.isEnabled()) return chain.proceed(request);

        DebugEvent event = DebugEvent.http(request.method(), request.url().toString()).lang(DebugEvent.LANG_JAR);
        Request outgoing = captureRequest(event, request);

        long start = System.currentTimeMillis();
        Response response;
        try {
            response = chain.proceed(outgoing);
        } catch (IOException e) {
            event.ms(System.currentTimeMillis() - start);
            event.error = e.getClass().getSimpleName();
            event.msg = e.getMessage();
            event.reason = e instanceof SocketTimeoutException ? DebugEvent.REASON_HTTP_TIMEOUT : DebugEvent.REASON_HTTP_ERROR;
            DebugLogStore.addEvent(event);
            throw e;
        }

        event.ms(System.currentTimeMillis() - start);
        event.code(response.code());
        captureResponse(event, response);
        if (response.code() >= 400) event.reason(DebugEvent.REASON_HTTP_STATUS);
        DebugLogStore.addEvent(event);
        return response;
    }

    /**
     * Fills in headers and body. Returns the request to actually send: when the body has to
     * be read it is rebuilt from the captured bytes, so a one-shot body is not consumed
     * twice. If the body cannot be read safely the original request is returned untouched.
     */
    private Request captureRequest(DebugEvent event, Request request) throws IOException {
        Headers headers = request.headers();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < headers.size() && i < MAX_HEADERS; i++) {
            if (lines.length() > 0) lines.append('\n');
            lines.append(headers.name(i)).append(": ").append(headers.value(i));
        }
        if (headers.size() > MAX_HEADERS) lines.append("\n…还有 ").append(headers.size() - MAX_HEADERS).append(" 项");
        if (lines.length() > 0) event.reqHeaders = lines.toString();

        RequestBody body = request.body();
        if (body == null) return request;

        MediaType mediaType = body.contentType();
        event.reqType = mediaType == null ? "" : mediaType.toString();

        if (body instanceof FormBody) {
            event.reqBody = formText((FormBody) body);
            return request;
        }
        if (body instanceof MultipartBody) {
            event.reqBody = "[multipart, " + ((MultipartBody) body).size() + " 个 part]";
            return request;
        }

        long length = body.contentLength();
        if (length > MAX_REQ_BODY) {
            event.truncated = true;
            event.reqBody = "[未捕获: " + length + " B，超过 " + MAX_REQ_BODY + " B 上限]";
            return request;
        }
        if (length < 0) {
            event.reqBody = "[未捕获: 长度未知（流式 body）]";
            return request;
        }

        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            byte[] bytes = buffer.readByteArray();
            event.reqBody = new String(bytes, charsetOf(mediaType));
            RequestBody replacement = RequestBody.create(mediaType, bytes);
            return request.newBuilder().method(request.method(), replacement).build();
        } catch (Throwable e) {
            event.reqBody = "[读取失败: " + e.getClass().getSimpleName() + "]";
            return request;
        }
    }

    private void captureResponse(DebugEvent event, Response response) throws IOException {
        Headers headers = response.headers();
        StringBuilder lines = new StringBuilder();
        appendHeader(lines, headers, "Content-Type");
        appendHeader(lines, headers, "Content-Length");
        appendHeader(lines, headers, "Content-Encoding");
        appendHeader(lines, headers, "Location");
        appendHeader(lines, headers, "Server");
        List<String> cookies = headers.values("Set-Cookie");
        if (!cookies.isEmpty()) {
            if (lines.length() > 0) lines.append('\n');
            lines.append("Set-Cookie: ").append(cookies.size()).append(" 条");
            for (String cookie : cookies) {
                lines.append('\n').append("  ").append(cookie);
            }
        }
        if (lines.length() > 0) event.resHeaders = lines.toString();

        ResponseBody body = response.body();
        if (body == null) return;

        MediaType mediaType = body.contentType();
        event.resType = mediaType == null ? "" : mediaType.toString();
        long length = body.contentLength();
        if (length > 0) event.bytes(length);

        if (isBinary(mediaType)) {
            event.resBody = "[binary " + (length > 0 ? length + " B" : "未知大小") + "]";
            return;
        }
        try {
            String text = peek(body, MAX_RES_BODY);
            if (length > MAX_RES_BODY) {
                event.truncated = true;
                event.resBody = text + "\n…[已截断，共 " + length + " B]";
            } else {
                event.resBody = text;
            }
        } catch (Throwable e) {
            event.resBody = "[读取失败: " + e.getClass().getSimpleName() + "]";
        }
    }

    /**
     * Reads up to {@code limit} bytes without consuming the body.
     *
     * <p>okhttp 5 removed {@code ResponseBody.peekBody}, so this goes through okio:
     * {@link BufferedSource#peek()} hands back a view that leaves the original source
     * position untouched, and {@code request(limit)} fills the internal buffer only as far
     * as {@code limit}. Cloning before {@code readString} keeps that buffer intact for the
     * real consumer downstream.
     */
    private static String peek(ResponseBody body, int limit) throws IOException {
        BufferedSource view = body.source().peek();
        view.request(limit);
        Buffer buffered = view.getBuffer();
        long count = Math.min(buffered.size(), limit);
        if (count <= 0) return "";
        Charset charset = charsetOf(body.contentType());
        return buffered.clone().readString(count, charset);
    }

    private static void appendHeader(StringBuilder lines, Headers headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isEmpty()) return;
        if (lines.length() > 0) lines.append('\n');
        lines.append(name).append(": ").append(value);
    }

    private static String formText(FormBody body) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            if (i > 0) builder.append('&');
            builder.append(body.name(i)).append('=').append(body.value(i));
        }
        return builder.toString();
    }

    private static Charset charsetOf(MediaType mediaType) {
        if (mediaType == null) return StandardCharsets.UTF_8;
        return mediaType.charset(StandardCharsets.UTF_8);
    }

    private static boolean isBinary(MediaType mediaType) {
        if (mediaType == null) return false;
        String type = mediaType.type().toLowerCase();
        String subtype = mediaType.subtype().toLowerCase();
        if ("video".equals(type) || "audio".equals(type) || "image".equals(type) || "font".equals(type)) return true;
        if (!"application".equals(type)) return false;
        return "octet-stream".equals(subtype) || "zip".equals(subtype) || "x-rar-compressed".equals(subtype)
                || "vnd.android.package-archive".equals(subtype) || "pdf".equals(subtype)
                || "x-7z-compressed".equals(subtype) || "gzip".equals(subtype);
    }
}
