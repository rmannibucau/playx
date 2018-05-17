package com.github.rmannibucau.playx.servlet.servlet.api;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServletResponse;

import com.github.rmannibucau.playx.servlet.servlet.internal.AsyncContextImpl;
import com.github.rmannibucau.playx.servlet.servlet.internal.DynamicServlet;
import com.github.rmannibucau.playx.servlet.servlet.internal.RequestAdapter;
import com.github.rmannibucau.playx.servlet.servlet.internal.RequestDispatcherImpl;
import com.github.rmannibucau.playx.servlet.servlet.internal.ResponseAdapter;
import com.typesafe.config.Config;

import play.api.inject.ApplicationLifecycle;
import play.api.inject.Injector;
import play.http.HttpEntity;
import play.mvc.Http;
import play.mvc.Result;

@Singleton
public class PlayServletContext implements ServletContext {

    private static final Logger LOGGER = Logger.getLogger(PlayServletContext.class.getName());

    private final Injector injector;

    private final String contextPath;

    private Executor executor;

    private final List<DynamicServlet> servlets = new ArrayList<>();

    private String requestEncoding = StandardCharsets.ISO_8859_1.name();

    private String responseEncoding = StandardCharsets.ISO_8859_1.name();

    @Inject
    public PlayServletContext(final ApplicationLifecycle lifecycle, final Injector injector,
            final Provider<Collection<ServletContainerInitializer>> initializersProvider, final Config config) {
        this.injector = injector;
        this.contextPath = safeConfigAccess(config, "playx.servlet.context", Config::getString).orElse("");
        if (safeConfigAccess(config, "playx.executor.default", Config::getBoolean).orElse(true)) {
            executor = ForkJoinPool.commonPool();
        } else {
            final int core = safeConfigAccess(config, "playx.executor.core", Config::getInt).orElse(64);
            final int max = safeConfigAccess(config, "playx.executor.max", Config::getInt).orElse(512);
            final int keepAlive = safeConfigAccess(config, "playx.executor.keepAlive.value", Config::getInt).orElse(60);
            final TimeUnit keepAliveUnit = safeConfigAccess(config, "playx.executor.keepAlive.unit",
                    (c, k) -> c.getEnum(TimeUnit.class, k)).orElse(TimeUnit.SECONDS);
            executor = new ThreadPoolExecutor(core, max, keepAlive, keepAliveUnit, new LinkedBlockingQueue<>(),
                    new ThreadFactory() {

                        private final AtomicInteger counter = new AtomicInteger();

                        @Override
                        public Thread newThread(final Runnable r) {
                            final Thread thread = new Thread(r);
                            thread.setDaemon(false);
                            thread.setPriority(Thread.NORM_PRIORITY);
                            thread.setName("playx-[context=" + contextPath + "]-" + counter.incrementAndGet());
                            return thread;
                        }
                    });
            lifecycle.addStopHook(
                    () -> CompletableFuture.runAsync(() -> ExecutorService.class.cast(executor).shutdownNow(), Runnable::run));
        }

        try {
            final Collection<ServletContainerInitializer> initializers = initializersProvider.get();
            initializers.forEach(i -> {
                try {
                    i.onStartup(findClasses(i), PlayServletContext.this);
                } catch (final ServletException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (final RuntimeException re) {
            return; // nothing to init
        }

        lifecycle.addStopHook(() -> CompletableFuture.runAsync(this::stop, getDefaultExecutor()));

        // we load them all anyway here, no lazy handling for now
        this.servlets.sort(comparing(DynamicServlet::getLoadOnStartup));
        this.servlets.forEach(s -> {
            try {
                s.getInstance().init(s.toServletConfig(this));
            } catch (final ServletException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private <T> Optional<T> safeConfigAccess(final Config config, final String key,
            final BiFunction<Config, String, T> extractor) {
        if (config.hasPathOrNull(key) && !config.getIsNull(key)) {
            return Optional.of(extractor.apply(config, key));
        }
        return Optional.empty();
    }

    private Set<Class<?>> findClasses(final ServletContainerInitializer i) {
        final HandlesTypes annotation = i.getClass().getAnnotation(HandlesTypes.class);
        if (annotation == null || annotation.value().length == 0) {
            return null;
        }
        return Stream.of(annotation.value()).flatMap(k -> Stream.of(findClasses(k))).collect(Collectors.toSet());
    }

    // kept as an extension point if needed to not be linked to a particular IoC here
    protected Class<?>[] findClasses(final Class<?> k) {
        return new Class<?>[0];
    }

    public void stop() {
        servlets.stream().sorted(comparing(DynamicServlet::getLoadOnStartup).reversed()).forEach(s -> s.getInstance().destroy());
    }

    CompletionStage<Result> invoke(final Http.RequestHeader requestHeader, final InputStream stream) {
        return findMatchingServlet(requestHeader).map(servlet -> executeInvoke(servlet, requestHeader, stream))
                .orElseGet(() -> CompletableFuture.completedFuture(new Result(HttpServletResponse.SC_NOT_FOUND)));
    }

    private CompletionStage<Result> executeInvoke(final DynamicServlet servlet, final Http.RequestHeader requestHeader,
            final InputStream stream) {
        final ResponseAdapter response = new ResponseAdapter(requestHeader.uri(), this);
        final RequestAdapter request = new RequestAdapter(requestHeader, stream, response, injector, this, servlet);
        request.setAttribute(ResponseAdapter.class.getName(), response);
        if (!servlet.isAsyncSupported()) {
            return CompletableFuture.supplyAsync(() -> doExecute(servlet, response, request), getDefaultExecutor())
                    .thenCompose(identity());
        }
        return doExecute(servlet, response, request);
    }

    private CompletionStage<Result> doExecute(final DynamicServlet servlet, final ResponseAdapter response,
            final RequestAdapter request) {
        try {
            servlet.getInstance().service(request, response);
        } catch (final ServletException | IOException ex) {
            if (request.isAsyncStarted() && AsyncContextImpl.class.isInstance(request.getAsyncContext())) {
                AsyncContextImpl.class.cast(request.getAsyncContext()).onError(ex);
            } else {
                response.fail(ex);
            }
            return CompletableFuture.completedFuture(new Result(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unexpected error",
                    emptyMap(), HttpEntity.fromString(ex.getMessage(), StandardCharsets.UTF_8.name())));
        } finally {
            if (!request.isAsyncStarted()) {
                response.onComplete();
            }
        }
        return response.toResult();
    }

    // todo: replace by servlet chain with filters
    private Optional<DynamicServlet> findMatchingServlet(final Http.RequestHeader requestHeader) {
        final URI uri = URI.create(requestHeader.uri());
        final String path = uri.getPath();
        if (!path.startsWith(getContextPath())) {
            return Optional.empty();
        }
        return findFirstMatchingServlet(path);
    }

    public Optional<DynamicServlet> findFirstMatchingServlet(final String path) {
        final String matching = path.substring(getContextPath().length());
        return servlets.stream().filter(s -> s.getMappings().stream().anyMatch(mapping -> isMatching(mapping, matching)))
                .findFirst();
    }

    // very light impl but should be enough here
    private boolean isMatching(final String mapping, final String request) {
        if (mapping.endsWith("/*")) {
            return request.startsWith(mapping.substring(0, mapping.length() - 1));
        }
        if (mapping.startsWith("*")) {
            return request.endsWith(mapping.substring(1));
        }
        return mapping.equals(request);
    }

    public Executor getDefaultExecutor() {
        return executor == null ? ForkJoinPool.commonPool() : executor;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String path) {
        return new RequestDispatcherImpl(this, path);
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public ServletContext getContext(final String uripath) {
        return this;
    }

    @Override
    public int getMajorVersion() {
        return 4;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 4;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getMimeType(final String file) {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(final String path) {
        return emptySet();
    }

    @Override
    public URL getResource(final String path) {
        return getClassLoader().getResource(path);
    }

    @Override
    public InputStream getResourceAsStream(final String path) {
        return getClassLoader().getResourceAsStream(path);
    }

    @Override
    public RequestDispatcher getNamedDispatcher(final String name) {
        return getRequestDispatcher("/");
    }

    @Override
    public Servlet getServlet(final String name) {
        return null;
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        return emptyEnumeration();
    }

    @Override
    public Enumeration<String> getServletNames() {
        return emptyEnumeration();
    }

    @Override
    public void log(final String msg) {
        LOGGER.info(msg);
    }

    @Override
    public void log(final Exception exception, final String msg) {
        LOGGER.log(Level.SEVERE, msg, exception);
    }

    @Override
    public void log(final String message, final Throwable throwable) {
        LOGGER.log(Level.SEVERE, message, throwable);
    }

    @Override
    public String getRealPath(final String path) {
        return null;
    }

    @Override
    public String getServerInfo() {
        return "Play-Servlet-Bridge/1.0";
    }

    @Override
    public String getInitParameter(final String name) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return emptyEnumeration();
    }

    @Override
    public boolean setInitParameter(final String name, final String value) {
        return false;
    }

    @Override
    public Object getAttribute(final String name) {
        return null;
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return emptyEnumeration();
    }

    @Override
    public void setAttribute(final String name, final Object object) {
        // no-op
    }

    @Override
    public void removeAttribute(final String name) {
        // no-op
    }

    @Override
    public String getServletContextName() {
        return "";
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final Servlet servlet) {
        final DynamicServlet dynamicServlet = new DynamicServlet(servletName, servlet);
        servlets.add(dynamicServlet);
        return dynamicServlet;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final String className) {
        try {
            return addServlet(servletName, (Class<? extends Servlet>) getClassLoader().loadClass(className));
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final Class<? extends Servlet> servletClass) {
        return addServlet(servletName, injector.instanceOf(servletClass));
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(final String jspName, final String jspFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Servlet> T createServlet(final Class<T> c) {
        return injector.instanceOf(c);
    }

    @Override
    public ServletRegistration getServletRegistration(final String servletName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return servlets.stream().flatMap(d -> d.getMappings().stream().map(e -> new AbstractMap.SimpleEntry<>(e, d)))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Filter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Filter> T createFilter(final Class<T> c) {
        return injector.instanceOf(c);
    }

    @Override
    public FilterRegistration getFilterRegistration(final String filterName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTrackingModes(final Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return emptySet();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return emptySet();
    }

    @Override
    public void addListener(final String className) {
        // no-op
    }

    @Override
    public <T extends EventListener> void addListener(final T t) {
        // no-op
    }

    @Override
    public void addListener(final Class<? extends EventListener> listenerClass) {
        // no-op
    }

    @Override
    public <T extends EventListener> T createListener(final Class<T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void declareRoles(final String... roleNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVirtualServerName() {
        return "localhost";
    }

    @Override
    public int getSessionTimeout() {
        return 30;
    }

    @Override
    public void setSessionTimeout(final int sessionTimeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestCharacterEncoding() {
        return requestEncoding;
    }

    @Override
    public void setRequestCharacterEncoding(final String encoding) {
        this.requestEncoding = encoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(final String encoding) {
        responseEncoding = encoding;
    }
}
