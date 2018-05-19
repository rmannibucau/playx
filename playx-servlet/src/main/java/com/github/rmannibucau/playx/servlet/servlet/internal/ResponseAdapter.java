package com.github.rmannibucau.playx.servlet.servlet.internal;

import static java.util.Optional.ofNullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import akka.util.ByteString;
import play.http.HttpEntity;
import play.mvc.Result;

public class ResponseAdapter implements HttpServletResponse {

    private final String requestUri;

    private final Collection<Cookie> cookies = new ArrayList<>();

    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private final ServletContext context;

    private int status = HttpServletResponse.SC_OK;

    private boolean commited;

    private String encoding;

    private int bufferSize = 8192;

    private Locale locale;

    private OutputStreamAdapter outputStream;

    private PrintWriter writer;

    // todo: stream?
    private ByteArrayOutputStream output = new ByteArrayOutputStream();

    private final CompletableFuture<Result> completion = new CompletableFuture<>();

    public ResponseAdapter(final String requestUri, final ServletContext context) {
        this.requestUri = requestUri;
        this.context = context;
    }

    public CompletionStage<Result> toResult() {
        return completion;
    }

    public void fail(final Throwable error) {
        if (completion.isDone()) {
            return;
        }
        try {
            flushBuffer();
        } catch (final IOException e) {
            // no-op
        }
        completion.completeExceptionally(error);
    }

    public void onComplete() {
        if (completion.isDone()) {
            return;
        }
        try {
            flushBuffer();
        } catch (final IOException e) {
            // no-op
        }
        headers.remove("Content-Type");
        completion.complete(new Result(status, headers,
                new HttpEntity.Strict(ByteString.fromArray(output.toByteArray()), ofNullable(getContentType()))));
    }

    private String base() {
        final URI uri = URI.create(requestUri);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String toEncoded(final String url) {
        return url;
    }

    @Override
    public void addCookie(final Cookie cookie) {
        cookies.add(cookie);
    }

    @Override
    public boolean containsHeader(final String name) {
        return headers.containsKey(name);
    }

    @Override
    public String encodeURL(final String s) {
        return toEncoded(s);
    }

    @Override
    public String encodeRedirectURL(final String s) {
        return toEncoded(s);
    }

    @Override
    public String encodeUrl(final String s) {
        return toEncoded(s);
    }

    @Override
    public String encodeRedirectUrl(final String s) {
        return encodeRedirectURL(s);
    }

    @Override
    public void sendError(final int sc, final String msg) {
        sendError(sc);
    }

    @Override
    public void sendError(final int sc) {
        setStatus(sc);
    }

    @Override
    public void sendRedirect(final String location) throws IOException {
        if (commited) {
            throw new IllegalStateException("response already committed");
        }
        resetBuffer();

        try {
            setStatus(SC_FOUND);

            setHeader("Location", base() + toEncoded(location));
        } catch (final IllegalArgumentException e) {
            setStatus(SC_NOT_FOUND);
        }
    }

    @Override
    public void setDateHeader(final String name, final long date) {
        addDateHeader(name, date);
    }

    @Override
    public void addDateHeader(final String name, final long date) {
        setHeader(name, Long.toString(date));
    }

    @Override
    public void setHeader(final String name, final String value) {
        addHeader(name, value);
    }

    @Override
    public void addHeader(final String name, final String value) {
        headers.put(name, value);
    }

    @Override
    public void setIntHeader(final String name, final int value) {
        addIntHeader(name, value);
    }

    @Override
    public void addIntHeader(final String name, final int value) {
        headers.put(name, Integer.toString(value));
    }

    @Override
    public void setStatus(final int sc) {
        status = sc;
    }

    @Override
    public void setStatus(final int sc, final String sm) {
        setStatus(sc);
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getHeader(final String name) {
        return headers.get(name);
    }

    @Override
    public Collection<String> getHeaders(final String name) {
        return ofNullable(headers.get(name)).map(Collections::singleton).orElseGet(Collections::emptySet);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public String getCharacterEncoding() {
        return ofNullable(encoding).orElseGet(context::getResponseCharacterEncoding);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return outputStream == null ? outputStream = new OutputStreamAdapter(output) : outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return writer == null ? writer = new PrintWriter(getOutputStream()) : writer;
    }

    @Override
    public void setCharacterEncoding(final String charset) {
        encoding = charset;
    }

    @Override
    public void setContentLength(final int len) {
        setHeader("Content-Length", Integer.toString(len));
    }

    @Override
    public void setContentLengthLong(final long length) {
        setHeader("Content-Length", Long.toString(length));
    }

    @Override
    public void setContentType(final String type) {
        setHeader("Content-Type", type);
    }

    @Override
    public void setBufferSize(final int size) {
        if (outputStream == null && writer == null) {
            output = new ByteArrayOutputStream(size);
            bufferSize = size;
        }
    }

    @Override
    public int getBufferSize() {
        return output.size();
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        output.flush();
    }

    @Override
    public void resetBuffer() {
        output.reset();
    }

    @Override
    public boolean isCommitted() {
        return commited;
    }

    @Override
    public void reset() {
        output.reset();
    }

    @Override
    public void setLocale(final Locale loc) {
        locale = loc;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }
}
