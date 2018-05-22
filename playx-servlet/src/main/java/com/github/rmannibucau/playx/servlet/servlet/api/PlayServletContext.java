package com.github.rmannibucau.playx.servlet.servlet.api;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rmannibucau.playx.servlet.servlet.internal.AsyncContextImpl;
import com.github.rmannibucau.playx.servlet.servlet.internal.DynamicFilter;
import com.github.rmannibucau.playx.servlet.servlet.internal.DynamicServlet;
import com.github.rmannibucau.playx.servlet.servlet.internal.FilterChainImpl;
import com.github.rmannibucau.playx.servlet.servlet.internal.RequestAdapter;
import com.github.rmannibucau.playx.servlet.servlet.internal.RequestDispatcherImpl;
import com.github.rmannibucau.playx.servlet.servlet.internal.ResponseAdapter;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueType;

import play.Environment;
import play.api.inject.ApplicationLifecycle;
import play.api.inject.Injector;
import play.http.HttpEntity;
import play.mvc.Http;
import play.mvc.Result;

@Singleton
public class PlayServletContext implements ServletContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayServletContext.class.getName());

    private final Injector injector;

    private final String contextPath;

    private Executor executor;

    private final List<DynamicServlet> servlets = new ArrayList<>();
    private final List<DynamicFilter> filters = new ArrayList<>();
    private final Collection<EventListener> listeners = new ArrayList<>();

    private String requestEncoding = StandardCharsets.ISO_8859_1.name();

    private String responseEncoding = StandardCharsets.ISO_8859_1.name();

    @Inject
    public PlayServletContext(final ApplicationLifecycle lifecycle, final Injector injector, final Config config) {
        this.injector = injector;
        this.contextPath = safeConfigAccess(config, "playx.servlet.context", Config::getString).orElse("");

        final int core = safeConfigAccess(config, "playx.servlet.executor.core", Config::getInt).orElse(64);
        final int max = safeConfigAccess(config, "playx.servlet.executor.max", Config::getInt).orElse(512);
        final int keepAlive = safeConfigAccess(config, "playx.servlet.executor.keepAlive.value", Config::getInt).orElse(60);
        final TimeUnit keepAliveUnit = safeConfigAccess(config, "playx.servlet.executor.keepAlive.unit",
                (c, k) -> c.getEnum(TimeUnit.class, k)).orElse(TimeUnit.SECONDS);
        executor = new ThreadPoolExecutor(core, max, keepAlive, keepAliveUnit, new LinkedBlockingQueue<>(),
                new ThreadFactory() {

                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable r) {
                        final Thread thread = new Thread(r);
                        thread.setDaemon(false);
                        thread.setPriority(Thread.NORM_PRIORITY);
                        thread.setName("playx-servlet-[context=" + contextPath + "]-" + counter.incrementAndGet());
                        return thread;
                    }
                });
        lifecycle.addStopHook(
                () -> CompletableFuture.runAsync(() -> ExecutorService.class.cast(executor).shutdownNow(), Runnable::run));

        lifecycle.addStopHook(() -> CompletableFuture.runAsync(this::stop, getDefaultExecutor()));

        safeConfigAccess(config, "playx.servlet.listeners", Config::getStringList)
                .ifPresent(clazz -> clazz.forEach(init -> {
                    final ClassLoader classLoader = getClassLoader();
                    try {
                        final Class<? extends EventListener> initializer =
                                (Class<? extends EventListener>) classLoader.loadClass(init.trim());
                        final EventListener listener = initializer.getConstructor().newInstance();
                        addListener(listener);
                    } catch (final Exception e) {
                        throw new IllegalArgumentException(e);
                    }
                }));

        safeConfigAccess(config, "playx.servlet.servlets", Config::getObjectList).ifPresent(servlets -> {
            servlets.forEach(servlet -> {
                final String clazz = requireNonNull(servlet.get("className").unwrapped().toString(),
                        "className must be provided: " + servlet);
                final String name = ofNullable(servlet.get("name")).map(c -> c.unwrapped().toString()).orElse(clazz);
                final boolean asyncSupported = extractAsyncSupported(servlet);
                final int loadOnStartup = ofNullable(servlet.get("loadOnStartup"))
                        .filter(c -> c.valueType() == ConfigValueType.NUMBER)
                        .map(c -> Number.class.cast(c.unwrapped()).intValue()).orElse(0);
                final Map<String, String> initParams = extractInitParams(servlet);
                final String[] mappings = extractMappings(servlet);

                final ClassLoader classLoader = getClassLoader();
                final Class<? extends Servlet> loadedClass;
                try {
                    loadedClass = (Class<? extends Servlet>) classLoader.loadClass(clazz.trim());
                } catch (final ClassNotFoundException e) {
                    throw new IllegalArgumentException(e);
                }

                final ServletRegistration.Dynamic dynamic = addServlet(name, loadedClass);
                dynamic.setLoadOnStartup(loadOnStartup);
                dynamic.addMapping(mappings);
                initParams.forEach(dynamic::setInitParameter);
                if (asyncSupported) {
                    dynamic.setAsyncSupported(true);
                } else {
                    LOGGER.info("Servlet {} is not set with asyncSupported=true", name);
                }
            });
        });

        safeConfigAccess(config, "playx.servlet.filters", Config::getObjectList).ifPresent(filters -> {
            filters.forEach(filter -> {
                final String clazz = requireNonNull(filter.get("className").unwrapped().toString(),
                        "className must be provided: " + filter);
                final String name = ofNullable(filter.get("name")).map(c -> c.unwrapped().toString()).orElse(clazz);
                final boolean asyncSupported = extractAsyncSupported(filter);
                final Map<String, String> initParams = extractInitParams(filter);
                final String[] mappings = extractMappings(filter);

                final ClassLoader classLoader = getClassLoader();
                final Class<? extends Filter> loadedClass;
                try {
                    loadedClass = (Class<? extends Filter>) classLoader.loadClass(clazz.trim());
                } catch (final ClassNotFoundException e) {
                    throw new IllegalArgumentException(e);
                }

                final FilterRegistration.Dynamic dynamic = addFilter(name, loadedClass);
                dynamic.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, mappings);
                initParams.forEach(dynamic::setInitParameter);
                if (asyncSupported) {
                    dynamic.setAsyncSupported(true);
                } else {
                    LOGGER.info("Filter {} is not set with asyncSupported=true", name);
                }
            });
        });

        // launch initializers
        safeConfigAccess(config, "playx.servlet.initializers", Config::getStringList)
                .ifPresent(clazz -> clazz.forEach(init -> {
                    final ClassLoader classLoader = getClassLoader();
                    try {
                        final Class<? extends ServletContainerInitializer> initializer =
                                (Class<? extends ServletContainerInitializer>) classLoader.loadClass(init.trim());
                        final ServletContainerInitializer instances = initializer.getConstructor()
                                                                                 .newInstance();
                        instances.onStartup(findClasses(instances), PlayServletContext.this);
                    } catch (final Exception e) {
                        throw new IllegalArgumentException(e);
                    }
                }));

        // start listeners
        if (!listeners.isEmpty()) {
            final ServletContextEvent event = new ServletContextEvent(this);
            listeners.stream()
                     .filter(ServletContextListener.class::isInstance)
                     .map(ServletContextListener.class::cast)
                     .forEach(l -> l.contextInitialized(event));
        }

        // filters
        this.filters.forEach(f -> {
            try {
                f.getInstance().init(f.toFilterConfig(this));
            } catch (final ServletException e) {
                throw new IllegalStateException(e);
            }
        });


        // we load all servlets anyway here, no lazy handling for now
        this.servlets.sort(comparing(DynamicServlet::getLoadOnStartup));
        this.servlets.forEach(s -> {
            try {
                s.getInstance().init(s.toServletConfig(this));
            } catch (final ServletException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Boolean extractAsyncSupported(ConfigObject filter) {
        return ofNullable(filter.get("asyncSupported"))
                .filter(c -> c.valueType() == ConfigValueType.BOOLEAN).map(c -> Boolean.class.cast(c.unwrapped()))
                .orElse(false);
    }

    private Map<String, String> extractInitParams(ConfigObject servlet) {
        return ofNullable(servlet.get("initParameters"))
                .filter(c -> c.valueType() == ConfigValueType.LIST).map(
                        list -> ConfigList.class.cast(list).stream()
                                                .filter(it -> it.valueType() == ConfigValueType.OBJECT).map(ConfigObject.class::cast)
                                                .collect(toMap(obj -> obj.get("name").unwrapped().toString(),
                                        obj -> obj.get("value").unwrapped().toString())))
                .orElseGet(Collections::emptyMap);
    }

    private String[] extractMappings(ConfigObject filter) {
        return ofNullable(filter.get("mappings")).filter(c -> c.valueType() == ConfigValueType.LIST)
                .map(list -> ConfigList.class.cast(list).stream().map(c -> c.unwrapped().toString())
                                             .toArray(String[]::new))
                .orElseGet(() -> new String[0]);
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
    protected Class<?>[] findClasses(final Class<?> k) { // TODO
        return new Class<?>[0];
    }

    public void stop() {
        // servlets destruction
        servlets.stream().sorted(comparing(DynamicServlet::getLoadOnStartup).reversed()).forEach(s -> s.getInstance().destroy());

        // filters destruction
        filters.forEach(f -> f.getInstance().destroy());

        // start listeners
        if (!listeners.isEmpty()) {
            final ServletContextEvent event = new ServletContextEvent(this);
            listeners.stream()
                     .filter(ServletContextListener.class::isInstance)
                     .map(ServletContextListener.class::cast)
                     .forEach(l -> l.contextDestroyed(event));
        }
    }

    public CompletionStage<Result> executeInvoke(final ServletMatching servlet, final Http.RequestHeader requestHeader,
            final InputStream stream, final String servletPath) {
        final Thread thread = Thread.currentThread();
        final ClassLoader contextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader());
        try {
            final ResponseAdapter response = new ResponseAdapter(
                    (requestHeader.secure() ? "https" : "http") + "://" + requestHeader.host() + requestHeader.uri(), this);
            final RequestAdapter request = new RequestAdapter(requestHeader, stream, response, injector, this, servlet.getDynamicServlet(), servletPath);
            request.setAttribute(ResponseAdapter.class.getName(), response);
            if (!servlet.getDynamicServlet().isAsyncSupported()) {
                return CompletableFuture.supplyAsync(() -> doExecute(servlet, response, request), getDefaultExecutor())
                        .thenCompose(identity());
            }
            return doExecute(servlet, response, request);
        } finally {
            thread.setContextClassLoader(contextClassLoader);
        }
    }

    private CompletionStage<Result> doExecute(final ServletMatching matched,
                                              final ResponseAdapter response,
                                              final RequestAdapter request) {
        try {
            if (matched.getDynamicFilters().isEmpty()) {
                matched.getDynamicServlet().getInstance().service(request, response);
            } else {
                new FilterChainImpl(matched.getDynamicFilters(), matched.getDynamicServlet()).doFilter(request, response);
            }
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

    public Optional<ServletMatching> findMatchingServlet(final Http.RequestHeader requestHeader) {
        final URI uri = URI.create(requestHeader.uri());
        final String path = uri.getPath();
        if (!path.startsWith(getContextPath())) {
            return Optional.empty();
        }
        return findFirstMatchingServlet(path);
    }

    public Optional<ServletMatching> findFirstMatchingServlet(final String path) {
        final String matching = path.substring(getContextPath().length());
        return servlets.stream()
                .map(servlet -> servlet.getMappings().stream().map(mapping -> findServletPath(mapping, matching))
                        .filter(Objects::nonNull).findFirst()
                       .map(mapping -> new ServletMatching(findMatchingFilters(servlet.getName(), path), servlet, mapping)))
                .filter(Optional::isPresent).findFirst().flatMap(identity());
    }

    private List<DynamicFilter> findMatchingFilters(final String servlet, final String path) {
        if (filters.isEmpty()) {
            return emptyList();
        }
        return filters.stream()
                .filter(f -> f.getServletNameMappings().contains(servlet)
                        || f.getUrlPatternMappings().stream().anyMatch(it -> findServletPath(it, path) != null))
                .collect(toList());
    }

    // very light impl but should be enough here - todo: pathinfo too
    // see org.apache.catalina.mapper.Mapper#addWrapper() and internalMapWrapper()
    private String findServletPath(final String mapping, final String request) {
        if (mapping.endsWith("/*")) {
            final String path = mapping.substring(0, mapping.length() - 1);
            if (request.startsWith(path)) {
                return path;
            }
            return null;
        }
        if (mapping.startsWith("*.")) {
            final String extension = mapping.substring(1);
            if (request.endsWith(extension)) {
                return request;
            }
            return null;
        }
        if (mapping.equals(request) || "/".equals(mapping) /* default */) {
            return mapping;
        }
        return null;
    }

    public Executor getDefaultExecutor() {
        return executor;
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
        LOGGER.error(msg, exception);
    }

    @Override
    public void log(final String message, final Throwable throwable) {
        LOGGER.error(message, throwable);
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
        return addServlet(servletName, createServlet(servletClass));
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(final String jspName, final String jspFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Servlet> T createServlet(final Class<T> c) {
        return newInstance(c);
    }

    @Override
    public ServletRegistration getServletRegistration(final String servletName) {
        return servlets.stream().filter(s -> s.getName().equals(servletName)).findFirst().orElse(null);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return servlets.stream().flatMap(d -> d.getMappings().stream().map(e -> new AbstractMap.SimpleEntry<>(e, d)))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final String className) {
        try {
            return addFilter(filterName, (Class<? extends Filter>) getClassLoader().loadClass(className));
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Filter filter) {
        final DynamicFilter dynamicFilter = new DynamicFilter(filterName, filter);
        filters.add(dynamicFilter);
        return dynamicFilter;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Class<? extends Filter> filterClass) {
        return addFilter(filterName, newInstance(filterClass));
    }

    @Override
    public <T extends Filter> T createFilter(final Class<T> c) {
        return newInstance(c);
    }

    @Override
    public FilterRegistration getFilterRegistration(final String filterName) {
        return filters.stream().filter(f -> f.getName().equals(filterName)).findFirst().orElse(null);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return filters.stream().flatMap(d -> d.getUrlPatternMappings().stream().map(e -> new AbstractMap.SimpleEntry<>(e, d)))
                      .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
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
        try {
            addListener((Class<? extends EventListener>) getClassLoader().loadClass(className.trim()));
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public <T extends EventListener> void addListener(final T t) {
        if (!ServletContextListener.class.isInstance(t)) {
            LOGGER.error("Unsupported listener: " + t + ", only ServletContextListener are supported for now");
        }
        listeners.add(t);
    }

    @Override
    public void addListener(final Class<? extends EventListener> listenerClass) {
        addListener(createListener(listenerClass));
    }

    @Override
    public <T extends EventListener> T createListener(final Class<T> c) {
        return newInstance(c);
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader() {
        return injector.instanceOf(Environment.class).classLoader();
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

    // todo: add a flag to ask to skip IoC?
    private <T> T newInstance(final Class<T> c) {
        try {
            return injector.instanceOf(c);
        } catch (final RuntimeException re) {
            try {
                return c.getConstructor().newInstance();
            } catch (final Exception e) {
                re.addSuppressed(e);
                throw re;
            }
        }
    }

    public static class ServletMatching {
        private final List<DynamicFilter> dynamicFilters;

        private final DynamicServlet dynamicServlet;

        private final String servletPath;

        private ServletMatching(final List<DynamicFilter> dynamicFilters,
                                final DynamicServlet dynamicServlet,
                                final String servletPath) {
            this.dynamicFilters = dynamicFilters;
            this.dynamicServlet = dynamicServlet;
            this.servletPath = servletPath;
        }

        public List<DynamicFilter> getDynamicFilters() {
            return dynamicFilters;
        }

        public DynamicServlet getDynamicServlet() {
            return dynamicServlet;
        }

        public String getServletPath() {
            return servletPath;
        }
    }
}
