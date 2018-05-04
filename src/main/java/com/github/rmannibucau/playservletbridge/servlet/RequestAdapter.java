package com.github.rmannibucau.playservletbridge.servlet;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.emptyList;
import static java.util.Collections.enumeration;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import org.apache.tomcat.util.http.FastHttpDateFormat;

import play.api.libs.typedmap.TypedKey;
import play.api.libs.typedmap.TypedMap;
import play.api.mvc.Request;
import play.mvc.Http;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.MapLike;
import scala.collection.Seq;
import scala.compat.java8.OptionConverters;

public class RequestAdapter implements HttpServletRequest {

    private static final TimeZone GMT_ZONE = TimeZone.getTimeZone("GMT");

    private static final DateFormat DATE_FORMATS[] = { new SimpleDateFormat(FastHttpDateFormat.RFC1123_DATE, Locale.US),
            new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US) };

    private static final ServletContext SERVLET_CONTEXT = new PlayServletContext();

    static {
        Stream.of(DATE_FORMATS).forEach(f -> f.setTimeZone(GMT_ZONE));
    }

    private final Request<Http.RequestBody> playDelegate;

    private ServletInputStream inputStream;

    private BufferedReader reader;

    public RequestAdapter(final Request<Http.RequestBody> request) {
        this.playDelegate = request;
    }

    @Override
    public Cookie[] getCookies() {
        return JavaConverters.asJavaCollection(playDelegate.cookies().toSeq()).stream().map(c -> new Cookie(c.name(), c.value()))
                .toArray(Cookie[]::new);
    }

    @Override
    public long getDateHeader(final String name) {
        final String value = getHeader(name);
        if (value == null) {
            return -1L;
        }
        final long result = parseDate(value);
        if (result != -1L) {
            return result;
        }
        throw new IllegalArgumentException(value);
    }

    @Override
    public String getHeader(final String name) {
        final Option<String> option = playDelegate.headers().get(name);
        return option.isDefined() ? option.get() : null;
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
        return enumeration(JavaConverters.asJavaCollection(playDelegate.headers().getAll(name)));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return enumeration(JavaConverters.asJavaCollection(playDelegate.headers().keys()));
    }

    @Override
    public int getIntHeader(final String name) {
        final String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override
    public String getMethod() {
        return playDelegate.method();
    }

    @Override
    public String getQueryString() {
        return playDelegate.rawQueryString();
    }

    @Override
    public String getPathInfo() {
        return "/";
    }

    @Override
    public String getPathTranslated() {
        return "/";
    }

    @Override
    public String getContextPath() {
        return SERVLET_CONTEXT.getContextPath();
    }

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public boolean isUserInRole(final String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return null;
    }

    @Override
    public String getRequestURI() {
        return playDelegate.uri();
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(playDelegate.uri());
    }

    @Override
    public String getServletPath() {
        return "/";
    }

    @Override
    public HttpSession getSession(final boolean create) {
        return null;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        return null;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return false;
    }

    @Override
    public boolean authenticate(final HttpServletResponse response) {
        return false;
    }

    @Override
    public void login(final String username, final String password) throws ServletException {
        throw new ServletException("Unsupported");
    }

    @Override
    public void logout() throws ServletException {
        throw new ServletException("Unsupported");
    }

    @Override
    public Collection<Part> getParts() {
        return emptyList();
    }

    @Override
    public Part getPart(final String name) {
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(final Class<T> httpUpgradeHandlerClass) throws ServletException {
        throw new ServletException("Unsupported");
    }

    @Override
    public Object getAttribute(final String name) {
        return playDelegate.attrs().get(TypedKey.apply(name));
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        final TypedMap attrs = playDelegate.attrs();
        if (MapLike.class.isInstance(attrs)) {
            final Collection<TypedKey<String>> keys = JavaConverters.asJavaCollection(MapLike.class.cast(attrs).keys());
            final Set<String> extractedKeys = keys.stream().map(tk -> OptionConverters.toJava(tk.displayName()))
                    .filter(Optional::isPresent).map(Optional::get).collect(toSet());
            return enumeration(extractedKeys);
        }
        return emptyEnumeration();
    }

    @Override
    public String getCharacterEncoding() {
        return OptionConverters.toJava(playDelegate.charset()).map(RequestAdapter::getCharsetFromContentType)
                .orElse(StandardCharsets.ISO_8859_1.name());
    }

    @Override
    public void setCharacterEncoding(final String env) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override
    public long getContentLengthLong() {
        return ofNullable(getHeader("content-length")).map(Long::parseLong).orElse(-1L);
    }

    @Override
    public String getContentType() {
        return OptionConverters.toJava(playDelegate.contentType()).orElse(null);
    }

    @Override
    public ServletInputStream getInputStream() {
        try {
            return inputStream == null
                    ? (inputStream = new InputStreamAdapter(playDelegate.body().asText(), getCharacterEncoding()))
                    : inputStream;
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return reader == null ? reader = new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding()))
                : reader;
    }

    @Override
    public String getParameter(final String name) {
        return OptionConverters.toJava(playDelegate.getQueryString(name)).orElseGet(
                () -> OptionConverters.toJava(playDelegate.contentType()).filter("multipart/form-data"::equalsIgnoreCase)
                        .map(ct -> playDelegate.body().asFormUrlEncoded().get(name)).filter(v -> v.length > 0).map(v -> v[0])
                        .orElse(null));
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return enumeration(Stream
                .concat(JavaConverters.asJavaCollection(playDelegate.queryString().keys()).stream(),
                        OptionConverters.toJava(playDelegate.contentType()).filter("multipart/form-data"::equalsIgnoreCase)
                                .map(ct -> playDelegate.body().asFormUrlEncoded().keySet().stream()).orElseGet(Stream::empty))
                .collect(toSet()));
    }

    @Override
    public String[] getParameterValues(final String name) {
        return OptionConverters.toJava(playDelegate.getQueryString(name)).map(v -> new String[] { v }).orElseGet(
                () -> OptionConverters.toJava(playDelegate.contentType()).filter("multipart/form-data"::equalsIgnoreCase)
                        .map(ct -> playDelegate.body().asFormUrlEncoded().get(name)).orElse(null));
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        final Stream<Map.Entry<String, String[]>> query = JavaConverters.mapAsJavaMap(playDelegate.queryString()).entrySet()
                .stream().map(kv -> new AbstractMap.SimpleEntry<>(kv.getKey(),
                        JavaConverters.asJavaCollection(kv.getValue()).toArray(new String[0])));
        final Stream<Map.Entry<String, String[]>> form = OptionConverters.toJava(playDelegate.contentType())
                .filter("multipart/form-data"::equalsIgnoreCase)
                .map(ct -> playDelegate.body().asFormUrlEncoded().entrySet().stream()).orElseGet(Stream::empty);
        return Stream.concat(query, form).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public String getScheme() {
        return playDelegate.secure() ? "https" : "http";
    }

    @Override
    public String getServerName() {
        return playDelegate.host();
    }

    @Override
    public int getServerPort() {
        return 8080; // TODO
    }

    @Override
    public String getRemoteAddr() {
        return playDelegate.remoteAddress();
    }

    @Override
    public String getRemoteHost() {
        final String host = playDelegate.host();
        return host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
    }

    @Override
    public void setAttribute(final String name, final Object o) {
        playDelegate.attrs().updated(TypedKey.apply(name), o);
    }

    @Override
    public void removeAttribute(final String name) {
        playDelegate.attrs().updated(TypedKey.apply(name), null);
    }

    @Override
    public Locale getLocale() {
        final Enumeration<Locale> locales = getLocales();
        return locales.hasMoreElements() ? locales.nextElement() : null;
    }

    @Override
    public Enumeration<Locale> getLocales() {
        final Seq<play.api.i18n.Lang> langSeq = playDelegate.acceptLanguages();
        return enumeration(JavaConverters.asJavaCollection(langSeq).stream().map(play.api.i18n.Lang::locale).collect(toSet()));
    }

    @Override
    public boolean isSecure() {
        return playDelegate.secure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRealPath(final String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getRemotePort() {
        final String host = playDelegate.host();
        return host.contains(":") ? Integer.parseInt(host.substring(host.indexOf(':') + 1)) : (isSecure() ? 443 : 80);
    }

    @Override
    public String getLocalName() {
        return "127.0.0.1";
    }

    @Override
    public String getLocalAddr() {
        return "127.0.0.1";
    }

    @Override
    public int getLocalPort() {
        return 80;
    }

    @Override
    public ServletContext getServletContext() {
        return SERVLET_CONTEXT;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse)
            throws IllegalStateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    private static long parseDate(final String value) {
        for (final DateFormat DATE_FORMAT : DATE_FORMATS) {
            try {
                return DATE_FORMAT.parse(value).getTime();
            } catch (final ParseException var5) {
                // no-op
            }
        }
        throw new IllegalArgumentException(value);
    }

    private static String getCharsetFromContentType(final String contentType) {
        if (contentType == null) {
            return null;
        } else {
            int start = contentType.indexOf("charset=");
            if (start < 0) {
                return null;
            } else {
                String encoding = contentType.substring(start + 8);
                int end = encoding.indexOf(59);
                if (end >= 0) {
                    encoding = encoding.substring(0, end);
                }

                encoding = encoding.trim();
                if (encoding.length() > 2 && encoding.startsWith("\"") && encoding.endsWith("\"")) {
                    encoding = encoding.substring(1, encoding.length() - 1);
                }

                return encoding.trim();
            }
        }
    }
}
