package com.github.rmannibucau.playx.cdi;

import static java.util.Collections.emptyMap;
import static java.util.Collections.list;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import akka.actor.ActorSystem;
import akka.stream.Materializer;
import controllers.Assets;
import controllers.AssetsConfiguration;
import controllers.DefaultAssetsMetadata;
import play.Application;
import play.ApplicationLoader;
import play.BuiltInComponentsFromContext;
import play.LoggerConfigurator;
import play.api.Configuration;
import play.api.Environment;
import play.api.Mode;
import play.api.OptionalSourceMapper;
import play.api.http.ActionCompositionConfiguration;
import play.api.http.CookiesConfiguration;
import play.api.http.EnabledFilters;
import play.api.http.FileMimeTypesConfiguration;
import play.api.http.FlashConfiguration;
import play.api.http.HttpConfiguration;
import play.api.http.HttpErrorHandler;
import play.api.http.HttpRequestHandler;
import play.api.http.ParserConfiguration;
import play.api.http.SecretConfiguration;
import play.api.http.SessionConfiguration;
import play.api.i18n.DefaultLangsProvider;
import play.api.i18n.DefaultMessagesApiProvider;
import play.api.inject.BindingKey;
import play.api.inject.QualifierAnnotation;
import play.api.inject.QualifierClass;
import play.api.inject.QualifierInstance;
import play.api.inject.RoutesProvider;
import play.api.libs.Files;
import play.api.libs.concurrent.ExecutionContextProvider;
import play.api.mvc.BodyParsers;
import play.api.mvc.CookieHeaderEncoding;
import play.api.mvc.DefaultActionBuilderImpl;
import play.api.mvc.DefaultControllerComponents;
import play.api.mvc.DefaultCookieHeaderEncoding;
import play.api.mvc.DefaultMessagesActionBuilderImpl;
import play.api.mvc.DefaultMessagesControllerComponents;
import play.api.mvc.DefaultPlayBodyParsers;
import play.api.mvc.MessagesControllerComponents;
import play.api.mvc.request.DefaultRequestFactory;
import play.api.mvc.request.RequestFactory;
import play.core.WebCommands;
import play.core.j.DefaultJavaContextComponents;
import play.core.j.JavaContextComponents;
import play.core.j.JavaHttpErrorHandlerAdapter;
import play.core.j.JavaRouterAdapter;
import play.i18n.Langs;
import play.i18n.MessagesApi;
import play.inject.ApplicationLifecycle;
import play.inject.Injector;
import play.libs.concurrent.DefaultFutures;
import play.libs.concurrent.HttpExecutionContext;
import play.libs.crypto.CSRFTokenSigner;
import play.libs.crypto.CookieSigner;
import play.mvc.EssentialFilter;
import play.mvc.FileMimeTypes;
import play.routing.Router;
import scala.Option;
import scala.collection.JavaConverters;
import scala.compat.java8.OptionConverters;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import scala.reflect.ClassTag;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ProcessBeanAttributes;
import javax.enterprise.inject.spi.configurator.BeanConfigurator;
import javax.inject.Singleton;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueType;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

public class CdiLoader implements ApplicationLoader {

    final BiFunction<Context, String, Class<?>> classLoader = (context, className) -> {
        try {
            return context.environment().classLoader().loadClass(className.trim());
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    };

    @Override
    public Application load(final Context context) {
        final Config config = context.initialConfig();
        final BiFunction<Boolean, String, Stream<Package>> packageLoader = (recursive, pckName) -> {
            final ClassLoader loader = context.environment().classLoader();
            final String trimmed = pckName.trim();
            try {
                return Stream.of(loader.loadClass(trimmed + ".package-info.class").getPackage());
            } catch (final ClassNotFoundException e) {
                return ofNullable(Package.getPackage(trimmed))
                        .map(Stream::of)
                        .orElseGet(() -> of(trimmed)
                                .map(name -> { // try to find a class - more relevant to load
                                    return findPackageFromClassLoader(loader, name, recursive).stream()
                                            .map(it -> classLoader.apply(context, it))
                                            .map(Class::getPackage);
                                })
                                .orElseThrow(() -> new IllegalArgumentException("can't find package: " + trimmed, e)));
            }
        };

        final SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .setClassLoader(context.environment().classLoader());
        if (safeConfigAccess(config, "playx.cdi.container.disableDiscovery", Config::getBoolean).orElse(false)) {
            initializer.disableDiscovery();
        }
        safeConfigAccess(config, "playx.cdi.container.beanClasses", Config::getStringList)
                .map(list -> list.stream().map(c -> classLoader.apply(context, c)).toArray(Class<?>[]::new))
                .ifPresent(initializer::addBeanClasses);
        safeConfigAccess(config, "playx.cdi.container.packages", Config::getList).ifPresent(pcks -> pcks.forEach(value -> {
            if (value.valueType() == ConfigValueType.OBJECT) {
                final ConfigObject object = ConfigObject.class.cast(value);
                final boolean recursive = safeConfigAccess(object.toConfig(), "recursive", Config::getBoolean).orElse(false);
                final String pck = safeConfigAccess(object.toConfig(), "package", Config::getString)
                        .orElseThrow(() -> new IllegalArgumentException("Missing package value in " + value))
                        .trim();
                packageLoader.apply(recursive, pck).forEach(pckIt -> initializer.addPackages(recursive, pckIt));
            } else if (value.valueType() == ConfigValueType.STRING) {
                packageLoader.apply(false, value.unwrapped().toString().trim()).forEach(initializer::addPackages);
            } else {
                throw new IllegalArgumentException("Unsupported configuration: " + value);
            }
        }));
        safeConfigAccess(config, "playx.cdi.container.properties", Config::getObjectList).ifPresent(properties -> properties
                .forEach(value -> initializer.addProperty(value.get("key").render(), value.get("value").unwrapped())));
        safeConfigAccess(config, "playx.cdi.container.extensions", Config::getStringList).ifPresent(extensions -> {
            final Class<? extends Extension>[] extInstances = extensions.stream().map(c -> classLoader.apply(context, c))
                    .toArray(Class[]::new);
            initializer.addExtensions(extInstances);
        });
        safeConfigAccess(config, "playx.cdi.container.decorators", Config::getStringList).ifPresent(extensions -> initializer
                .enableDecorators(extensions.stream().map(c -> classLoader.apply(context, c)).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.interceptors", Config::getStringList).ifPresent(extensions -> initializer
                .enableInterceptors(extensions.stream().map(c -> classLoader.apply(context, c)).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.alternatives", Config::getStringList).ifPresent(extensions -> initializer
                .selectAlternatives(extensions.stream().map(c -> classLoader.apply(context, c)).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.alternativeStereotypes", Config::getStringList).ifPresent(extensions -> {
            final Class<? extends Annotation>[] alternativeStereotypeClasses = extensions.stream()
                    .map(c -> classLoader.apply(context, c)).toArray(Class[]::new);
            initializer.selectAlternativeStereotypes(alternativeStereotypeClasses);
        });

        final CdiInjector injector = new CdiInjector();
        final Application application = new CdiApplication(injector, context);

        addProvidedBeans(context, initializer, injector, application);

        final SeContainer container = initializer.initialize();
        injector.container = container;
        context.applicationLifecycle().addStopHook(() -> CompletableFuture.runAsync(container::close, Runnable::run));
        return application;
    }

    private Collection<String> findPackageFromClassLoader(final ClassLoader loader, final String name, final boolean recursive) {
        final String pck = name.replace(".", "/");
        try {
            final Enumeration<URL> urls = loader.getResources(pck);
            while (urls.hasMoreElements()) {
                final File next = toFile(urls.nextElement());
                if (next != null && next.exists() && !next.isDirectory()) {
                    try (final JarFile file = new JarFile(next)) {
                        final Collection<JarEntry> entries = list(file.entries());
                        final Set<String> packages = new HashSet<>();
                        final List<String> classes = entries.stream()
                                .filter(it -> {
                                    final String eName = it.getName();
                                    return eName.startsWith(pck + '/') && eName.endsWith(".class") &&
                                            (recursive || eName.lastIndexOf('/', pck.length()) == pck.length());
                                })
                                .map(JarEntry::getName)
                                .map(clazz -> clazz.replace('/', '.').substring(0, clazz.length() - ".class".length()))
                                .filter(s -> {
                                    final String sPck = s.substring(0, s.lastIndexOf('.'));
                                    return packages.add(sPck);
                                })
                                .sorted()
                                .collect(toList());
                        if (name.equals(classes.get(0).substring(0, classes.get(0).lastIndexOf('.')))) {
                            return singletonList(classes.iterator().next());
                        }
                        // here we don't have any class in the root package so need to list them all
                        final Collection<String> pcks = classes.stream()
                                .map(clazz -> clazz.replace('/', '.').substring(0, clazz.length() - ".class".length()))
                                .collect(toSet());
                        final Collection<String> toDrop = new ArrayList<>();
                        for (final String c : pcks) {
                            if (pcks.stream().anyMatch(it -> !it.equals(c) && c.startsWith(it))) {
                                toDrop.add(c);
                            }
                        }
                        return classes.stream()
                                .filter(it -> toDrop.stream().anyMatch(it::startsWith))
                                .collect(toList());
                    }
                }
            }
        } catch (final IOException e1) {
            // no-op
        }
        return null;
    }

    private void addProvidedBeans(final Context context, final SeContainerInitializer initializer, final Injector injector,
                                  final Application application) {
        final play.Environment environment = context.environment();
        final Configuration configuration = Configuration.load(environment.asScala());
        final Config config = configuration.underlying();

        initializer.addExtensions(new Extension() { // todo: make it more configured and modular reusing

            void addSingletons(@Observes final BeforeBeanDiscovery event, final BeanManager beanManager) {
                // integration with servlet module
                final Collection<Class<?>> extensions = new ArrayList<>();
                try {
                    extensions.add(environment.classLoader()
                            .loadClass("com.github.rmannibucau.playx.servlet.servlet.api.ServletFilter"));
                } catch (final Exception e) {
                    // no-op
                }

                if (safeConfigAccess(context.initialConfig(), "playx.cdi.beans.defaults", Config::getBoolean).orElse(true)) {
                    Stream.concat(Stream.of(Assets.class, Files.DefaultTemporaryFileCreator.class,
                            Files.DefaultTemporaryFileReaper.class, DefaultPlayBodyParsers.class, BodyParsers.Default.class,
                            DefaultActionBuilderImpl.class, DefaultControllerComponents.class, DefaultMessagesActionBuilderImpl.class,
                            DefaultMessagesControllerComponents.class, DefaultFutures.class,
                            play.api.libs.concurrent.DefaultFutures.class, HttpExecutionContext.class, DefaultAssetsMetadata.class),
                            extensions.stream()).forEach(it -> event.addAnnotatedType(beanManager.createAnnotatedType(it)));
                }
            }

            void restrictTypesForDefaultMessagesControllerComponents(@Observes final ProcessBeanAttributes<DefaultMessagesControllerComponents> pba) {
                pba.configureBeanAttributes().types(MessagesControllerComponents.class, Object.class);
            }

            void addProvidedBeans(@Observes final AfterBeanDiscovery event, final BeanManager beanManager) {
                if (safeConfigAccess(context.initialConfig(), "playx.cdi.beans.defaults", Config::getBoolean).orElse(true)) {
                    addPlayBeans(event);
                }
                addCustomBeans(event, beanManager);
            }

            private void addCustomBeans(final AfterBeanDiscovery event, final BeanManager beanManager) {
                safeConfigAccess(context.initialConfig(), "playx.cdi.beans.customs", Config::getObjectList)
                        .ifPresent(beans -> beans.forEach(bean -> {
                            final String className = requireNonNull(bean.get("className"), "className can't be null: " + bean)
                                    .unwrapped().toString();
                            final Class beanClass = classLoader.apply(context, className);

                            final String scope = ofNullable(bean.get("scope")).map(s -> s.unwrapped().toString())
                                    .orElse("javax.enterprise.context.Dependent");
                            final Class<? extends Annotation> scopeAnnotation;
                            switch (scope) {
                                case "javax.enterprise.context.ApplicationScoped":
                                    scopeAnnotation = ApplicationScoped.class;
                                    break;
                                case "javax.inject.Singleton":
                                    scopeAnnotation = Singleton.class;
                                    break;
                                case "javax.enterprise.context.Dependent":
                                    scopeAnnotation = Dependent.class;
                                    break;
                                default:
                                    scopeAnnotation = (Class<? extends Annotation>) classLoader.apply(context, scope);
                            }

                            final BeanConfigurator<Object> configurator = event.addBean();
                            ofNullable(bean.get("id")).map(s -> s.unwrapped().toString()).ifPresent(configurator::id);
                            configurator.beanClass(beanClass).scope(scopeAnnotation);
                            ofNullable(bean.get("transitiveTypeClosure")).filter(v -> Boolean.class.cast(v.unwrapped()))
                                    .map(v -> configurator.addTransitiveTypeClosure(beanClass))
                                    .orElseGet(() -> configurator.types(beanClass, Object.class));
                            ofNullable(bean.get("qualifiers")).map(v -> {
                                throw new IllegalArgumentException("Not yet supported");
                            }).orElseGet(() -> configurator.qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE));

                            addBeanLifecycle(beanManager, beanClass, configurator);
                        }));
            }

            private <T> void addBeanLifecycle(final BeanManager beanManager, final Class<T> beanClass,
                                              final BeanConfigurator<T> configurator) {
                final AnnotatedType<T> annotatedType = beanManager.createAnnotatedType(beanClass);
                final InjectionTarget<T> injectionTarget = beanManager.createInjectionTarget(annotatedType);

                configurator.createWith(injectionTarget::produce).destroyWith((o, ctx) -> injectionTarget.dispose(o));
            }

            private void addPlayBeans(final AfterBeanDiscovery event) {
                // core
                addBean(event, new LazyProvider<>(() -> LoggerConfigurator.apply(environment.classLoader()).map(lc -> {
                    lc.configure(environment, context.initialConfig(), emptyMap());
                    return lc.loggerFactory();
                }).orElseGet(LoggerFactory::getILoggerFactory)), ILoggerFactory.class);

                addBean(event, () -> environment, play.Environment.class);
                addBean(event, environment::asScala, Environment.class);
                addBean(event, context::applicationLifecycle, ApplicationLifecycle.class);
                addBean(event, () -> context.applicationLifecycle().asScala(), play.api.inject.ApplicationLifecycle.class);
                addBean(event, () -> config, Config.class);
                addBean(event, () -> configuration, Configuration.class);
                addBean(event, () -> application, Application.class);
                addBean(event, application::asScala, play.api.Application.class);
                addBean(event, () -> injector, Injector.class);
                addBean(event, injector::asScala, play.api.inject.Injector.class);
                addBean(event, () -> context.asScala().webCommands(), WebCommands.class);
                addBean(event,
                        new LazyProvider<>(() -> new OptionalSourceMapper(OptionConverters.toScala(context.sourceMapper()))),
                        OptionalSourceMapper.class);

                // i18n
                final Supplier<play.api.i18n.Langs> langs = new LazyProvider<>(
                        () -> new DefaultLangsProvider(configuration).get());
                addBean(event, langs, play.api.i18n.Langs.class);
                addBean(event, () -> langs.get().asJava(), Langs.class);

                // built-in
                final BuiltInComponentsFromContext builtInComponentsFromContext = new BuiltInComponentsFromContext(context) {

                    private JavaRouterAdapter router;

                    @Override
                    public Router router() {
                        return router == null
                                ? router = new JavaRouterAdapter(new RoutesProvider(injector.asScala(), environment.asScala(),
                                configuration, httpConfiguration()).get())
                                : router;
                    }

                    @Override
                    public List<EssentialFilter> httpFilters() {
                        final EnabledFilters enabledFilters = new EnabledFilters(environment.asScala(), configuration,
                                injector.asScala());
                        return JavaConverters.asJavaCollection(enabledFilters.filters().toList()).stream()
                                .map(play.api.mvc.EssentialFilter::asJava).collect(toList());
                    }
                };

                final Supplier<HttpConfiguration> httpConfiguration = new LazyProvider<>(
                        builtInComponentsFromContext::httpConfiguration);
                final Supplier<CookiesConfiguration> cookiesConfiguration = new LazyProvider<>(
                        () -> httpConfiguration.get().cookies());

                final Supplier<play.api.i18n.MessagesApi> messagesApi = new LazyProvider<>(
                        () -> new DefaultMessagesApiProvider(environment.asScala(), configuration, langs.get(),
                                httpConfiguration.get()).get());
                addBean(event, messagesApi, play.api.i18n.MessagesApi.class);
                addBean(event, () -> messagesApi.get().asJava(), MessagesApi.class);

                addBean(event, httpConfiguration, HttpConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().parser()), ParserConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().session()), SessionConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().secret()), SecretConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().flash()), FlashConfiguration.class);
                addBean(event, cookiesConfiguration, CookiesConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().actionComposition()),
                        ActionCompositionConfiguration.class);
                addBean(event, new LazyProvider<>(() -> httpConfiguration.get().fileMimeTypes()),
                        FileMimeTypesConfiguration.class);
                addBean(event, new LazyProvider<>(() -> new DefaultCookieHeaderEncoding(cookiesConfiguration.get())),
                        CookieHeaderEncoding.class);
                addBean(event,
                        new LazyProvider<>(
                                () -> AssetsConfiguration.fromConfiguration(configuration, environment.mode().asScala())),
                        AssetsConfiguration.class);

                final Supplier<Files.TemporaryFileReaperConfiguration> instance = new LazyProvider<>(
                        () -> Files.TemporaryFileReaperConfiguration$.MODULE$.fromConfiguration(configuration));
                addBean(event, instance, Files.TemporaryFileReaperConfiguration.class);

                addBean(event, new LazyProvider<>(() -> builtInComponentsFromContext.router().asScala()),
                        play.api.routing.Router.class);
                addBean(event, new LazyProvider<>(builtInComponentsFromContext::router), Router.class);

                final Supplier<ActorSystem> actorSystem = new LazyProvider<>(builtInComponentsFromContext::actorSystem);
                addBean(event, actorSystem, ActorSystem.class);
                addBean(event, new LazyProvider<>(builtInComponentsFromContext::materializer), Materializer.class);

                final Supplier<ExecutionContextExecutor> executionContextExecutor = new LazyProvider<>(
                        () -> new ExecutionContextProvider(actorSystem.get()).get());
                addBean(event, executionContextExecutor, ExecutionContextExecutor.class, Executor.class, ExecutionContext.class);

                final Supplier<play.http.HttpRequestHandler> httpRequestHandler = new LazyProvider<>(
                        builtInComponentsFromContext::httpRequestHandler);
                addBean(event, httpRequestHandler, play.http.HttpRequestHandler.class);
                addBean(event, new LazyProvider<>(() -> httpRequestHandler.get().asScala()), HttpRequestHandler.class);

                final Supplier<RequestFactory> requestFactory = new LazyProvider<>(
                        () -> new DefaultRequestFactory(httpConfiguration.get()));
                addBean(event, requestFactory, RequestFactory.class);

                final Supplier<FileMimeTypes> fileMimeTypes = new LazyProvider<>(builtInComponentsFromContext::fileMimeTypes);
                addBean(event, fileMimeTypes, FileMimeTypes.class);
                addBean(event, new LazyProvider<>(() -> fileMimeTypes.get().asScala()), play.api.http.FileMimeTypes.class);

                final Supplier<JavaContextComponents> javaContextComponents = new LazyProvider<>(
                        () -> new DefaultJavaContextComponents(messagesApi.get().asJava(), langs.get().asJava(),
                                fileMimeTypes.get(), httpConfiguration.get()));

                final Supplier<play.http.HttpErrorHandler> errorHandler = new LazyProvider<>(
                        builtInComponentsFromContext::httpErrorHandler);
                addBean(event,
                        new LazyProvider<>(
                                () -> new JavaHttpErrorHandlerAdapter(errorHandler.get(), javaContextComponents.get())),
                        HttpErrorHandler.class);
                addBean(event, errorHandler, play.http.HttpErrorHandler.class);

                final Supplier<CookieSigner> cookieSigner = new LazyProvider<>(builtInComponentsFromContext::cookieSigner);
                addBean(event, cookieSigner, CookieSigner.class);
                addBean(event, () -> cookieSigner.get().asScala(), play.api.libs.crypto.CookieSigner.class);

                final Supplier<CSRFTokenSigner> csrfTokenSigner = new LazyProvider<>(
                        builtInComponentsFromContext::csrfTokenSigner);
                addBean(event, csrfTokenSigner, CSRFTokenSigner.class);
                addBean(event, () -> csrfTokenSigner.get().asScala(), play.api.libs.crypto.CSRFTokenSigner.class);
            }

            private <T> void addBean(final AfterBeanDiscovery event, final Supplier<T> instance, final Class<T> mainApi,
                                     final Class<?>... types) {
                event.addBean().id(toId(mainApi)).beanClass(Injector.class)
                        .types(Stream.concat(Stream.of(mainApi), Stream.concat(Stream.of(types), Stream.of(Object.class)))
                                .toArray(Class[]::new))
                        .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
                        .scope(Dependent.class/* to avoid proxies, singleton in practise cause we return a single instance */)
                        .createWith(ctx -> instance.get());
            }

            private <T> String toId(final Class<T> type) {
                return "playx.cdi.beans.builtin." + type.getName();
            }
        });
    }

    private static <T> Optional<T> safeConfigAccess(final Config config, final String key,
                                                    final BiFunction<Config, String, T> extractor) {
        if (config.hasPathOrNull(key) && !config.getIsNull(key)) {
            return Optional.of(extractor.apply(config, key));
        }
        return Optional.empty();
    }

    private static class CdiInjector implements Injector {

        private final CdiScalaInjector scala;

        private SeContainer container;

        private CdiInjector() {
            this.scala = new CdiScalaInjector(this);
        }

        @Override
        public <T> T instanceOf(final Class<T> clazz) {
            return container.select(clazz).get();
        }

        @Override
        public <T> T instanceOf(final BindingKey<T> key) {
            return (key.qualifier().isEmpty() ? container.select(key.clazz())
                    : container.select(key.clazz(), asQualifier(key.qualifier().get()))).get();
        }

        @Override
        public play.api.inject.Injector asScala() {
            return scala;
        }

        private Annotation asQualifier(final QualifierAnnotation qualifierAnnotation) {
            return QualifierInstance.class.isInstance(qualifierAnnotation)
                    ? Annotation.class.cast(QualifierInstance.class.cast(qualifierAnnotation).instance())
                    : () -> QualifierClass.class.cast(qualifierAnnotation).clazz();
        }
    }

    private static class CdiScalaInjector implements play.api.inject.Injector {

        private final CdiInjector java;

        private CdiScalaInjector(final CdiInjector cdiInjector) {
            this.java = cdiInjector;
        }

        @Override
        public <T> T instanceOf(final ClassTag<T> tag) {
            return (T) java.instanceOf(tag.runtimeClass());
        }

        @Override
        public <T> T instanceOf(final Class<T> clazz) {
            return java.instanceOf(clazz);
        }

        @Override
        public <T> T instanceOf(final BindingKey<T> key) {
            return java.instanceOf(key);
        }

        @Override
        public Injector asJava() {
            return java;
        }
    }

    private static class CdiScalaApplication implements play.api.Application {

        private final CdiApplication java;

        private CdiScalaApplication(final CdiApplication cdiApplication) {
            this.java = cdiApplication;
        }

        @Override
        public ActorSystem actorSystem() {
            return java.injector.instanceOf(ActorSystem.class);
        }

        @Override
        public Materializer materializer() {
            return java.injector.instanceOf(Materializer.class);
        }

        @Override
        public RequestFactory requestFactory() {
            return java.injector.instanceOf(RequestFactory.class);
        }

        @Override
        public HttpRequestHandler requestHandler() {
            return java.injector.instanceOf(HttpRequestHandler.class);
        }

        @Override
        public HttpErrorHandler errorHandler() {
            return java.injector.instanceOf(HttpErrorHandler.class);
        }

        @Override
        public HttpConfiguration httpConfiguration() {
            return java.injector.instanceOf(HttpConfiguration.class);
        }

        @Override
        public Future<?> stop() {
            return java.context.applicationLifecycle().asScala().stop();
        }

        @Override
        public boolean globalApplicationEnabled() {
            return safeConfigAccess(java.context.initialConfig(), "play.cdi.allowGlobalApplication", Config::getBoolean)
                    .orElse(false);
        }

        @Override
        public File path() {
            return java.context.environment().rootPath();
        }

        @Override
        public ClassLoader classloader() {
            return java.context.environment().classLoader();
        }

        @Override
        public Mode mode() {
            return java.context.environment().mode().asScala();
        }

        @Override
        public Environment environment() {
            return java.context.environment().asScala();
        }

        @Override
        public boolean isDev() {
            return java.context.environment().isDev();
        }

        @Override
        public boolean isTest() {
            return java.context.environment().isTest();
        }

        @Override
        public boolean isProd() {
            return java.context.environment().isProd();
        }

        @Override
        public Configuration configuration() {
            return new Configuration(java.context.initialConfig());
        }

        @Override
        public Application asJava() {
            return java;
        }

        @Override
        public File getFile(final String relativePath) {
            return java.context.environment().getFile(relativePath);
        }

        @Override
        public Option<File> getExistingFile(final String relativePath) {
            return java.context.environment().asScala().getExistingFile(relativePath);
        }

        @Override
        public Option<URL> resource(final String name) {
            return java.context.environment().asScala().resource(name);
        }

        @Override
        public Option<InputStream> resourceAsStream(final String name) {
            return java.context.environment().asScala().resourceAsStream(name);
        }

        @Override
        public play.api.inject.Injector injector() {
            return java.injector.asScala();
        }
    }

    private static class CdiApplication implements Application {

        private final play.api.Application scala;

        private final Injector injector;

        private final Context context;

        private CdiApplication(final Injector injector, final Context context) {
            this.injector = injector;
            this.context = context;
            this.scala = new CdiScalaApplication(this);
        }

        @Override
        public play.api.Application getWrappedApplication() {
            return asScala();
        }

        @Override
        public play.api.Application asScala() {
            return scala;
        }

        @Override
        public Config config() {
            return context.initialConfig();
        }

        @Override
        public Injector injector() {
            return injector;
        }
    }

    private static class LazyProvider<T> implements Supplier<T> {

        private final AtomicReference<T> ref = new AtomicReference<>();

        private final Supplier<T> delegate;

        private LazyProvider(final Supplier<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public T get() {
            T val = ref.get();
            if (val == null) {
                synchronized (this) {
                    val = ref.get();
                    if (val == null) {
                        val = delegate.get();
                        if (!ref.compareAndSet(null, val)) {
                            val = ref.get();
                        }
                    }
                }
            }
            return val;
        }
    }

    private static File toFile(final URL url) {
        if ("jar".equals(url.getProtocol())) {
            try {
                final String spec = url.getFile();
                final int separator = spec.indexOf('!');
                if (separator == -1) {
                    return null;
                }
                return toFile(new URL(spec.substring(0, separator + 1)));
            } catch (final MalformedURLException e) {
                return null;
            }
        } else if ("file".equals(url.getProtocol())) {
            String path = decode(url.getFile());
            if (path.endsWith("!")) {
                path = path.substring(0, path.length() - 1);
            }
            return new File(path);
        }
        return null;
    }

    private static String decode(final String fileName) {
        if (fileName.indexOf('%') == -1) {
            return fileName;
        }

        final StringBuilder result = new StringBuilder(fileName.length());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int i = 0; i < fileName.length(); ) {
            final char c = fileName.charAt(i);

            if (c == '%') {
                out.reset();
                do {
                    if (i + 2 >= fileName.length()) {
                        throw new IllegalArgumentException("Incomplete % sequence at: " + i);
                    }

                    final int d1 = Character.digit(fileName.charAt(i + 1), 16);
                    final int d2 = Character.digit(fileName.charAt(i + 2), 16);

                    if (d1 == -1 || d2 == -1) {
                        throw new IllegalArgumentException("Invalid % sequence (" + fileName.substring(i, i + 3) + ") at: " + String.valueOf(i));
                    }

                    out.write((byte) ((d1 << 4) + d2));

                    i += 3;

                } while (i < fileName.length() && fileName.charAt(i) == '%');


                result.append(out.toString());

                continue;
            } else {
                result.append(c);
            }

            i++;
        }
        return result.toString();
    }
}
