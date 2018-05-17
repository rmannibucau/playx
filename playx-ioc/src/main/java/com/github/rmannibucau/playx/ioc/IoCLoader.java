package com.github.rmannibucau.playx.ioc;

import static java.util.Locale.ROOT;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.typesafe.config.Config;

import akka.actor.ActorSystem;
import akka.stream.Materializer;
import play.Application;
import play.ApplicationLoader;
import play.DefaultApplication;
import play.api.Configuration;
import play.api.Environment;
import play.api.Mode;
import play.api.http.HttpConfiguration;
import play.api.http.HttpErrorHandler;
import play.api.http.HttpRequestHandler;
import play.api.inject.BindingKey;
import play.api.mvc.request.RequestFactory;
import play.inject.Injector;
import scala.Option;
import scala.concurrent.Future;
import scala.reflect.ClassTag;

public class IoCLoader implements ApplicationLoader {

    @Override
    public Application load(final Context context) {
        final Config config = context.initialConfig();
        final Collection<String> loaders = safeConfigAccess(config, "playx.ioc.loaders", Config::getStringList)
                .orElseGet(Collections::emptyList);
        if (loaders.isEmpty()) {
            throw new IllegalArgumentException("No loader set for playx.ioc.loaders");
        }

        final Map<ApplicationLoader, Application> instances = loaders.stream().map(clazz -> {
            try {
                return ApplicationLoader.class
                        .cast(context.environment().classLoader().loadClass(clazz).getConstructor().newInstance());
            } catch (final ClassNotFoundException | InstantiationException | InvocationTargetException | NoSuchMethodException
                    | IllegalAccessException e) {
                throw new IllegalArgumentException(e);
            }
        }).collect(toMap(identity(), l -> l.load(context), (o, o2) -> {
            throw new IllegalStateException("Conflicting keys for " + o + " || " + o2);
        }, LinkedHashMap::new));

        final Map<String, String> routingTable = safeConfigAccess(config, "playx.ioc.routing", Config::getObjectList)
                .orElseGet(Collections::emptyList).stream()
                .collect(toMap(it -> it.keySet().iterator().next(), it -> it.values().iterator().next().render()));

        return new IoCApplication(instances, routingTable).asJava();
    }

    private <T> Optional<T> safeConfigAccess(final Config config, final String key,
            final BiFunction<Config, String, T> extractor) {
        if (config.hasPathOrNull(key) && !config.getIsNull(key)) {
            return Optional.of(extractor.apply(config, key));
        }
        return Optional.empty();
    }

    private static class IoCApplication implements play.api.Application {

        private final Collection<Application> delegates;

        private final play.api.inject.Injector injector;

        private final play.api.Application scalaRef;

        private final Application java;

        IoCApplication(final Map<ApplicationLoader, Application> values, final Map<String, String> routing) {
            this.delegates = new ArrayList<>(values.values());
            this.injector = new IoCInjector(
                    values.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().injector(), (a, b) -> {
                        throw new IllegalStateException("Conflicting keys for " + a + "/" + b);
                    }, LinkedHashMap::new)), routing);
            this.scalaRef = delegates.iterator().next().asScala();
            this.java = new DefaultApplication(this, injector.asJava());
        }

        @Override
        public play.api.inject.Injector injector() {
            return injector;
        }

        @Override
        public File path() {
            return scalaRef.path();
        }

        @Override
        public ClassLoader classloader() {
            return scalaRef.classloader();
        }

        @Override
        public Mode mode() {
            return scalaRef.mode();
        }

        @Override
        public Environment environment() {
            return scalaRef.environment();
        }

        @Override
        public boolean isDev() {
            return scalaRef.isDev();
        }

        @Override
        public boolean isTest() {
            return scalaRef.isTest();
        }

        @Override
        public boolean isProd() {
            return scalaRef.isProd();
        }

        @Override
        public Configuration configuration() {
            return scalaRef.configuration();
        }

        @Override
        public ActorSystem actorSystem() {
            return scalaRef.actorSystem();
        }

        @Override
        public Materializer materializer() {
            return scalaRef.materializer();
        }

        @Override
        public RequestFactory requestFactory() {
            return scalaRef.requestFactory();
        }

        @Override
        public HttpRequestHandler requestHandler() {
            return scalaRef.requestHandler();
        }

        @Override
        public HttpErrorHandler errorHandler() {
            return scalaRef.errorHandler();
        }

        @Override
        public Application asJava() {
            return java;
        }

        @Override
        public File getFile(final String relativePath) {
            return scalaRef.getFile(relativePath);
        }

        @Override
        public Option<File> getExistingFile(final String relativePath) {
            return scalaRef.getExistingFile(relativePath);
        }

        @Override
        public Option<URL> resource(final String name) {
            return scalaRef.resource(name);
        }

        @Override
        public Option<InputStream> resourceAsStream(final String name) {
            return scalaRef.resourceAsStream(name);
        }

        @Override
        public Future<?> stop() {
            return scalaRef.stop();
        }

        @Override
        public HttpConfiguration httpConfiguration() {
            return scalaRef.httpConfiguration();
        }

        @Override
        public boolean globalApplicationEnabled() {
            return scalaRef.globalApplicationEnabled();
        }
    }

    private static class IoCInjector implements Injector, play.api.inject.Injector {

        private final Map<ApplicationLoader, Injector> injectors;

        private final Map<String, String> routing;

        private IoCInjector(final Map<ApplicationLoader, Injector> injectors, final Map<String, String> routing) {
            this.injectors = injectors;
            this.routing = routing;
        }

        @Override
        public <T> T instanceOf(final ClassTag<T> tag) {
            return (T) instanceOf(tag.runtimeClass());
        }

        @Override
        public <T> T instanceOf(final Class<T> clazz) {
            return instanceOf(BindingKey.apply(clazz));
        }

        @Override
        public <T> T instanceOf(final BindingKey<T> key) {
            final Collection<RuntimeException> errors = new ArrayList<>();
            for (final Injector delegate : sortInjectorsFor(key.clazz().getName())) {
                try {
                    return delegate.instanceOf(key);
                } catch (final RuntimeException re) {
                    errors.add(re);
                }
            }
            final RuntimeException error = errors.iterator().next();
            errors.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }

        private Collection<Injector> sortInjectorsFor(final String name) {
            if (routing.isEmpty()) {
                return injectors.values();
            }
            final Optional<Collection<Injector>> sortedInjectors = routing.entrySet().stream()
                    .filter(e -> name.startsWith(e.getKey())).findFirst().map(Map.Entry::getValue)
                    .map(preference -> this.injectors.entrySet().stream().sorted((e1, e2) -> {
                        final String e1Clazz = e1.getKey().getClass().getSimpleName();
                        if (e1Clazz.toLowerCase(ROOT).contains(preference)) {
                            return -1;
                        }
                        final String e2Clazz = e2.getKey().getClass().getSimpleName();
                        if (e2Clazz.toLowerCase(ROOT).contains(preference)) {
                            return 1;
                        }
                        return e1Clazz.compareTo(e2Clazz);
                    }).map(Map.Entry::getValue).collect(toList()));
            return sortedInjectors.orElseGet(this.injectors::values);
        }

        @Override
        public Injector asJava() {
            return this;
        }

        @Override
        public play.api.inject.Injector asScala() {
            return this;
        }
    }
}
