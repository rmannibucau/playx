package com.github.rmannibucau.playx.servlet.servlet.internal;

import static java.util.Collections.emptyList;
import static java.util.Collections.enumeration;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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

import play.api.inject.Injector;
import play.i18n.Lang;
import play.libs.typedmap.TypedKey;
import play.mvc.Http;

public class RequestAdapter implements HttpServletRequest {

    private static final TimeZone GMT_ZONE = TimeZone.getTimeZone("GMT");

    private static final DateFormat DATE_FORMATS[] = { new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
            new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US) };

    static {
        Stream.of(DATE_FORMATS).forEach(f -> f.setTimeZone(GMT_ZONE));
    }

    private final Supplier<DateFormat[]> dateFormats;

    private final Http.RequestHeader playDelegate;

    private final InputStream entity;

    private final ServletResponse response;

    private final Injector injector;

    private final ServletContext context;

    private final DynamicServlet servlet;

    private final String servletPath;

    private final String facingServletPath;

    private ServletInputStream inputStream;

    private BufferedReader reader;

    private final Map<String, Object> attributes = new HashMap<>();

    private boolean asyncStarted;

    private Map<String, String> params;

    public RequestAdapter(final Http.RequestHeader request, final InputStream entity, final ServletResponse response,
            final Injector injector, final ServletContext context, final DynamicServlet servlet, final String servletPath) {
        this.context = context;
        this.playDelegate = request;
        this.entity = entity;
        this.response = response;
        this.injector = injector;
        this.servlet = servlet;
        this.servletPath = servletPath;
        this.facingServletPath = servletPath.endsWith("/") ? servletPath.substring(0, servletPath.length() - 1) : servletPath;
        this.dateFormats = new Supplier<DateFormat[]>() { // lazy
            private DateFormat[] formats;

            @Override
            public DateFormat[] get() {
                if (formats == null) {
                    formats = Stream.of(DATE_FORMATS).map(DateFormat::clone).map(DateFormat.class::cast).toArray(DateFormat[]::new);
                }
                return formats;
            }
        };
        parseParams();
    }

    @Override
    public Cookie[] getCookies() {
        return StreamSupport.stream(playDelegate.cookies().spliterator(), false).map(c -> new Cookie(c.name(), c.value()))
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
        final List<String> option = playDelegate.getHeaders().getAll(name);
        return option != null && !option.isEmpty() ? option.iterator().next() : null;
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
        return enumeration(playDelegate.getHeaders().getAll(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return enumeration(playDelegate.getHeaders().toMap().keySet());
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
        final String uri = playDelegate.uri();
        final int questionMark = uri.indexOf('?');
        return questionMark >= 0 ? uri.substring(questionMark + 1) : "";
    }

    @Override // part of the request path not part of the context path and servlet path
    public String getPathInfo() {
        if ("/".equals(servletPath)) { // default servlet, particular case
            return null;
        }
        final String servletUri = getRequestURI().substring(getContextPath().length());
        if (servletUri.startsWith(servletPath)) { // /xxx/* matching
            return servletUri.substring(servletPath.endsWith("/") ? servletPath.length() - 1 : servletPath.length());
        }
        return null;
    }

    @Override
    public String getPathTranslated() {
        return "/";
    }

    @Override
    public String getContextPath() {
        return context.getContextPath();
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
        final String uri = playDelegate.uri();
        final String queryString = getQueryString();
        return uri.substring(0, uri.length() - queryString.length() - (queryString.isEmpty() ? 0 : 1));
    }

    @Override
    public StringBuffer getRequestURL() {
        final StringBuffer base = new StringBuffer(getScheme()).append("://").append(getServerName());
        final int serverPort = getServerPort();
        if (serverPort != 80 && serverPort != 443) {
            base.append(':').append(serverPort);
        }
        return base.append(getRequestURI());
    }

    @Override
    public String getServletPath() {
        return facingServletPath;
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
        return ofNullable(attributes.get(name))
                .orElseGet(() -> playDelegate.attrs().getOptional(TypedKey.create(name)).orElse(null));
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return enumeration(attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return playDelegate.charset().orElseGet(() -> ofNullable(RequestAdapter.getCharsetFromContentType(getContentType()))
                .orElse(getServletContext().getRequestCharacterEncoding()));
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
        return playDelegate.contentType().orElse(null);
    }

    @Override
    public ServletInputStream getInputStream() {
        return inputStream == null ? (inputStream = new InputStreamAdapter(entity)) : inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return reader == null ? reader = new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding()))
                : reader;
    }

    @Override
    public String getParameter(final String name) {
        return ofNullable(playDelegate.getQueryString(name)).orElseGet(() -> playDelegate.contentType()
                .filter("multipart/form-data"::equalsIgnoreCase).map(ct -> params.get(name)).orElse(null));
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return enumeration(Stream.concat(playDelegate.queryString().keySet().stream(), playDelegate.contentType()
                .filter("multipart/form-data"::equalsIgnoreCase).map(ct -> params.keySet().stream()).orElseGet(Stream::empty))
                .collect(toSet()));
    }

    @Override
    public String[] getParameterValues(final String name) {
        return ofNullable(playDelegate.getQueryString(name)).map(v -> new String[] { v })
                .orElseGet(() -> playDelegate.contentType().filter("multipart/form-data"::equalsIgnoreCase)
                        .map(ct -> params.get(name)).map(v -> new String[] { v }).orElse(null));
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        final Stream<Map.Entry<String, String[]>> query = playDelegate.queryString().entrySet().stream();
        final Stream<AbstractMap.SimpleEntry<String, String[]>> form = playDelegate.contentType()
                .filter("multipart/form-data"::equalsIgnoreCase)
                .map(ct -> params.entrySet().stream()
                        .map(v -> new AbstractMap.SimpleEntry<>(v.getKey(), new String[] { v.getValue() })))
                .orElseGet(Stream::empty);
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
        final String host = playDelegate.host();
        final int sep = host.indexOf(':');
        return sep < 0 ? host : host.substring(0, sep);
    }

    @Override
    public int getServerPort() {
        final String host = playDelegate.host();
        final int sep = host.indexOf(':');
        return sep < 0 ? (playDelegate.secure() ? 443 : 80) : Integer.parseInt(host.substring(sep + 1));
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
        attributes.put(name, o);
    }

    @Override
    public void removeAttribute(final String name) {
        attributes.remove(name);
    }

    @Override
    public Locale getLocale() {
        final Enumeration<Locale> locales = getLocales();
        return locales.hasMoreElements() ? locales.nextElement() : null;
    }

    @Override
    public Enumeration<Locale> getLocales() {
        final List<Lang> langSeq = playDelegate.acceptLanguages();
        return enumeration(langSeq.stream().map(play.api.i18n.Lang::locale).collect(toSet()));
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
        return context;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        asyncStarted = true;
        return startAsync(this, response);
    }

    @Override
    public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse)
            throws IllegalStateException {
        asyncStarted = true;
        return new AsyncContextImpl(servletRequest,
                ResponseAdapter.class.cast(servletRequest.getAttribute(ResponseAdapter.class.getName())), servletResponse,
                servletRequest == this, injector, servlet).start();
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncStarted;
    }

    @Override
    public boolean isAsyncSupported() {
        return true;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    private void parseParams() {
        if (params == null) {
            params = new HashMap<>();
            final String method = getMethod();
            if (method != null && (!method.equals("GET") && !method.equals("DELETE") && !method.equals("HEAD")
                    && !method.equals("OPTIONS"))) {
                final String contentType = getContentType();
                if (contentType != null && (contentType.contains("application/x-www-form-urlencoded")
                        || contentType.contains("multipart/form-data"))) {
                    try (final BufferedReader r = new BufferedReader(new InputStreamReader(entity))) {
                        final StringTokenizer parameters = new StringTokenizer(r.lines().collect(joining("\n")), "&");
                        while (parameters.hasMoreTokens()) {
                            final StringTokenizer param = new StringTokenizer(parameters.nextToken(), "=");
                            String name = URLDecoder.decode(param.nextToken(), "UTF-8");
                            if (name == null) {
                                break;
                            }

                            final String value;
                            if (param.hasMoreTokens()) {
                                value = URLDecoder.decode(param.nextToken(), "UTF-8");
                            } else {
                                value = "";
                            }

                            params.put(name, value == null ? "" : value);
                        }
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                }
            }
        }
    }

    private long parseDate(final String value) {
        for (final DateFormat format : dateFormats.get()) {
            try {
                return format.parse(value).getTime();
            } catch (final ParseException var5) {
                // no-op
            }
        }
        throw new IllegalArgumentException(value);
    }

    private static String getCharsetFromContentType(final String contentType) {
        if (contentType == null) {
            return null;
        }

        final int start = contentType.indexOf("charset=");
        if (start < 0) {
            return null;
        }

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
