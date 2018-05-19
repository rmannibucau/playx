package com.github.rmannibucau.playx.servlet.servlet.api;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import play.Environment;
import play.api.inject.ApplicationLifecycle;
import play.api.inject.Injector;
import play.http.HttpEntity;
import play.mvc.Http;
import play.mvc.Result;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
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
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PlayServletContext implements ServletContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayServletContext.class.getName());

    private final Injector injector;

    private final String contextPath;

    private Executor executor;

    private final List<DynamicServlet> servlets = new ArrayList<>();

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

        safeConfigAccess(config, "playx.servlet.servlets", Config::getObjectList).ifPresent(servlets -> {
            servlets.forEach(servlet -> {
                final String clazz = requireNonNull(servlet.get("className").unwrapped().toString(),
                        "className must be provided: " + servlet);
                final String name = ofNullable(servlet.get("name")).map(c -> c.unwrapped().toString()).orElse(clazz);
                final boolean asyncSupported = ofNullable(servlet.get("asyncSupported"))
                        .filter(c -> c.valueType() == ConfigValueType.BOOLEAN).map(c -> Boolean.class.cast(c.unwrapped()))
                        .orElse(false);
                final int loadOnStartup = ofNullable(servlet.get("loadOnStartup"))
                        .filter(c -> c.valueType() == ConfigValueType.NUMBER)
                        .map(c -> Number.class.cast(c.unwrapped()).intValue()).orElse(0);
                final Map<String, String> initParams = ofNullable(servlet.get("initParameters"))
                        .filter(c -> c.valueType() == ConfigValueType.LIST).map(
                                list -> ConfigList.class.cast(list).stream()
                                        .filter(it -> it.valueType() == ConfigValueType.OBJECT).map(ConfigObject.class::cast)
                                        .collect(toMap(obj -> obj.get("name").unwrapped().toString(),
                                                obj -> obj.get("value").unwrapped().toString())))
                        .orElseGet(Collections::emptyMap);
                final String[] mappings = ofNullable(servlet.get("mappings")).filter(c -> c.valueType() == ConfigValueType.LIST)
                        .map(list -> ConfigList.class.cast(list).stream().map(c -> c.unwrapped().toString())
                                .toArray(String[]::new))
                        .orElseGet(() -> new String[0]);

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
    protected Class<?>[] findClasses(final Class<?> k) { // TODO
        return new Class<?>[0];
    }

    public void stop() {
        servlets.stream().sorted(comparing(DynamicServlet::getLoadOnStartup).reversed()).forEach(s -> s.getInstance().destroy());
    }

    public CompletionStage<Result> executeInvoke(final DynamicServlet servlet, final Http.RequestHeader requestHeader,
            final InputStream stream, final String servletPath) {
        final Thread thread = Thread.currentThread();
        final ClassLoader contextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader());
        try {
            final ResponseAdapter response = new ResponseAdapter(
                    (requestHeader.secure() ? "https" : "http") + "://" + requestHeader.host() + requestHeader.uri(), this);
            final RequestAdapter request = new RequestAdapter(requestHeader, stream, response, injector, this, servlet, servletPath);
            request.setAttribute(ResponseAdapter.class.getName(), response);
            if (!servlet.isAsyncSupported()) {
                return CompletableFuture.supplyAsync(() -> doExecute(servlet, response, request), getDefaultExecutor())
                        .thenCompose(identity());
            }
            return doExecute(servlet, response, request);
        } finally {
            thread.setContextClassLoader(contextClassLoader);
        }
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
                        .filter(Objects::nonNull).findFirst().map(mapping -> new ServletMatching(servlet, mapping)))
                .filter(Optional::isPresent).findFirst().flatMap(identity());
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
        return newInstance(c);
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

        private final DynamicServlet dynamicServlet;

        private final String servletPath;

        private ServletMatching(final DynamicServlet dynamicServlet, final String servletPath) {
            this.dynamicServlet = dynamicServlet;
            this.servletPath = servletPath;
        }

        public DynamicServlet getDynamicServlet() {
            return dynamicServlet;
        }

        public String getServletPath() {
            return servletPath;
        }
    }
}
