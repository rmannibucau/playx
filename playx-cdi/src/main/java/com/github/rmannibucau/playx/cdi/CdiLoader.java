package com.github.rmannibucau.playx.cdi;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueType;

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
import scala.compat.java8.OptionConverters;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import scala.reflect.ClassTag;

public class CdiLoader implements ApplicationLoader {

    @Override
    public Application load(final Context context) {
        final Config config = context.initialConfig();
        final Function<String, Class<?>> classLoader = s -> {
            try {
                return context.environment().classLoader().loadClass(s.trim());
            } catch (final ClassNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
        };
        final Function<String, Package> packageLoader = s -> {
            try {
                return context.environment().classLoader().loadClass(s.trim() + ".package-info.class").getPackage();
            } catch (final ClassNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
        };

        final SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .setClassLoader(context.environment().classLoader());
        if (safeConfigAccess(config, "playx.cdi.container.disableDiscovery", Config::getBoolean).orElse(false)) {
            initializer.disableDiscovery();
        }
        safeConfigAccess(config, "playx.cdi.container.beanClasses", Config::getStringList)
                .map(list -> list.stream().map(classLoader).toArray(Class<?>[]::new)).ifPresent(initializer::addBeanClasses);
        safeConfigAccess(config, "playx.cdi.container.packages", Config::getList).ifPresent(pcks -> pcks.forEach(value -> {
            if (value.valueType() == ConfigValueType.OBJECT) {
                final ConfigObject object = ConfigObject.class.cast(value);
                final boolean recursive = safeConfigAccess(object.toConfig(), "recursive", Config::getBoolean).orElse(false);
                final String pck = safeConfigAccess(object.toConfig(), "package", Config::getString)
                        .orElseThrow(() -> new IllegalArgumentException("Missing package value in " + value));
                initializer.addPackages(recursive, packageLoader.apply(pck.trim()));
            } else if (value.valueType() == ConfigValueType.STRING) {
                initializer.addPackages(packageLoader.apply(value.render().trim()));
            } else {
                throw new IllegalArgumentException("Unsupported configuration: " + value);
            }
        }));
        safeConfigAccess(config, "playx.cdi.container.properties", Config::getObjectList).ifPresent(properties -> properties
                .forEach(value -> initializer.addProperty(value.get("key").render(), value.get("value").unwrapped())));
        safeConfigAccess(config, "playx.cdi.container.extensions", Config::getStringList).ifPresent(extensions -> {
            final Class<? extends Extension>[] extInstances = extensions.stream().map(classLoader).toArray(Class[]::new);
            initializer.addExtensions(extInstances);
        });
        safeConfigAccess(config, "playx.cdi.container.decorators", Config::getStringList).ifPresent(
                extensions -> initializer.enableDecorators(extensions.stream().map(classLoader).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.interceptors", Config::getStringList).ifPresent(
                extensions -> initializer.enableInterceptors(extensions.stream().map(classLoader).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.alternatives", Config::getStringList).ifPresent(
                extensions -> initializer.selectAlternatives(extensions.stream().map(classLoader).toArray(Class[]::new)));
        safeConfigAccess(config, "playx.cdi.container.alternativeStereotypes", Config::getStringList).ifPresent(extensions -> {
            final Class<? extends Annotation>[] alternativeStereotypeClasses = extensions.stream().map(classLoader)
                    .toArray(Class[]::new);
            initializer.selectAlternativeStereotypes(alternativeStereotypeClasses);
        });

        final CdiInjector injector = new CdiInjector();
        final Application application = new CdiApplication(injector, context);

        if (safeConfigAccess(config, "playx.cdi.beans.defaults", Config::getBoolean).orElse(true)) {
            addDefaultBeans(context, initializer, injector, application);
        }

        final SeContainer container = initializer.initialize();
        injector.container = container;
        context.applicationLifecycle().addStopHook(() -> CompletableFuture.runAsync(container::close, Runnable::run));
        return application;
    }

    private void addDefaultBeans(final Context context, final SeContainerInitializer initializer, final Injector injector,
            final Application application) {
        final Config config = context.initialConfig();
        final Configuration configuration = context.asScala().initialConfiguration();
        final play.Environment environment = context.environment();

        initializer.addExtensions(new Extension() { // todo: make it more configured and modular reusing

            void addSingletons(@Observes final BeforeBeanDiscovery event, final BeanManager beanManager) {
                Stream.of(Assets.class, Files.DefaultTemporaryFileCreator.class, Files.DefaultTemporaryFileReaper.class,
                        DefaultPlayBodyParsers.class, BodyParsers.Default.class, DefaultActionBuilderImpl.class,
                        DefaultControllerComponents.class, DefaultMessagesActionBuilderImpl.class,
                        DefaultMessagesControllerComponents.class, DefaultFutures.class,
                        play.api.libs.concurrent.DefaultFutures.class, HttpExecutionContext.class,
                        DefaultAssetsMetadata.class)
                        .forEach(it -> event.addAnnotatedType(beanManager.createAnnotatedType(it)));
            }

            void addProvidedBeans(@Observes final AfterBeanDiscovery event) {
                // core
                addBean(event, LoggerConfigurator.apply(environment.classLoader()).map(lc -> {
                    lc.configure(environment, context.initialConfig(), emptyMap());
                    return lc.loggerFactory();
                }).orElseGet(LoggerFactory::getILoggerFactory), ILoggerFactory.class);

                addBean(event, environment, play.Environment.class);
                addBean(event, environment.asScala(), Environment.class);
                addBean(event, context.applicationLifecycle(), ApplicationLifecycle.class);
                addBean(event, context.applicationLifecycle().asScala(), play.api.inject.ApplicationLifecycle.class);
                addBean(event, config, Config.class);
                addBean(event, configuration, Configuration.class);
                addBean(event, application, Application.class);
                addBean(event, application.asScala(), play.api.Application.class);
                addBean(event, injector, Injector.class);
                addBean(event, injector.asScala(), play.api.inject.Injector.class);
                addBean(event, context.asScala().webCommands(), WebCommands.class);
                addBean(event, new OptionalSourceMapper(OptionConverters.toScala(context.sourceMapper())),
                        OptionalSourceMapper.class);

                // i18n
                final play.api.i18n.Langs langs = new DefaultLangsProvider(configuration).get();
                addBean(event, langs, play.api.i18n.Langs.class);
                addBean(event, langs.asJava(), Langs.class);

                final play.api.i18n.MessagesApi messagesApi = new DefaultMessagesApiProvider(environment.asScala(), configuration,
                        langs, null).get();
                addBean(event, messagesApi.asJava(), MessagesApi.class);
                addBean(event, messagesApi, play.api.i18n.MessagesApi.class);

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
                        return emptyList();
                    }
                };

                final HttpConfiguration httpConfiguration = builtInComponentsFromContext.httpConfiguration();
                final CookiesConfiguration cookiesConfiguration = httpConfiguration.cookies();

                addBean(event, httpConfiguration, HttpConfiguration.class);
                addBean(event, httpConfiguration.parser(), ParserConfiguration.class);
                addBean(event, httpConfiguration.session(), SessionConfiguration.class);
                addBean(event, httpConfiguration.secret(), SecretConfiguration.class);
                addBean(event, httpConfiguration.flash(), FlashConfiguration.class);
                addBean(event, cookiesConfiguration, CookiesConfiguration.class);
                addBean(event, httpConfiguration.actionComposition(), ActionCompositionConfiguration.class);
                addBean(event, httpConfiguration.fileMimeTypes(), FileMimeTypesConfiguration.class);
                addBean(event, new DefaultCookieHeaderEncoding(cookiesConfiguration), CookieHeaderEncoding.class);
                addBean(event, AssetsConfiguration.fromConfiguration(configuration, environment.mode().asScala()), AssetsConfiguration.class);

                final Files.TemporaryFileReaperConfiguration instance = Files.TemporaryFileReaperConfiguration$.MODULE$
                        .fromConfiguration(configuration);
                addBean(event, instance, Files.TemporaryFileReaperConfiguration.class);

                addBean(event, builtInComponentsFromContext.router().asScala(), play.api.routing.Router.class);
                addBean(event, builtInComponentsFromContext.router(), Router.class);

                final ActorSystem actorSystem = builtInComponentsFromContext.actorSystem();
                addBean(event, actorSystem, ActorSystem.class);
                addBean(event, builtInComponentsFromContext.materializer(), Materializer.class);

                final ExecutionContextExecutor executionContextExecutor = new ExecutionContextProvider(actorSystem).get();
                addBean(event, executionContextExecutor, ExecutionContextExecutor.class, Executor.class, ExecutionContext.class);

                final play.http.HttpRequestHandler httpRequestHandler = builtInComponentsFromContext.httpRequestHandler();
                addBean(event, httpRequestHandler, play.http.HttpRequestHandler.class);
                addBean(event, httpRequestHandler.asScala(), HttpRequestHandler.class);

                final RequestFactory requestFactory = new DefaultRequestFactory(httpConfiguration);
                addBean(event, requestFactory, RequestFactory.class);

                final FileMimeTypes fileMimeTypes = builtInComponentsFromContext.fileMimeTypes();
                addBean(event, fileMimeTypes, FileMimeTypes.class);
                addBean(event, fileMimeTypes.asScala(), play.api.http.FileMimeTypes.class);

                final JavaContextComponents javaContextComponents = new DefaultJavaContextComponents(messagesApi.asJava(),
                        langs.asJava(), fileMimeTypes, httpConfiguration);

                final play.http.HttpErrorHandler errorHandler = builtInComponentsFromContext.httpErrorHandler();
                addBean(event, new JavaHttpErrorHandlerAdapter(errorHandler, javaContextComponents), HttpErrorHandler.class);
                addBean(event, errorHandler, play.http.HttpErrorHandler.class);

                final CookieSigner cookieSigner = builtInComponentsFromContext.cookieSigner();
                addBean(event, cookieSigner, CookieSigner.class);
                addBean(event, cookieSigner.asScala(), play.api.libs.crypto.CookieSigner.class);

                final CSRFTokenSigner csrfTokenSigner = builtInComponentsFromContext.csrfTokenSigner();
                addBean(event, csrfTokenSigner, CSRFTokenSigner.class);
                addBean(event, csrfTokenSigner.asScala(), play.api.libs.crypto.CSRFTokenSigner.class);
            }

            private <T> void addBean(final AfterBeanDiscovery event, final T instance, final Class<T> mainApi,
                    final Class<?>... types) {
                event.addBean().id(toId(mainApi)).beanClass(Injector.class)
                        .types(Stream.concat(Stream.of(mainApi), Stream.concat(Stream.of(types), Stream.of(Object.class)))
                                .toArray(Class[]::new))
                        .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
                        .scope(Dependent.class/* to avoid proxies, singleton in practise cause we return a single instance */)
                        .createWith(ctx -> instance);
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
}
