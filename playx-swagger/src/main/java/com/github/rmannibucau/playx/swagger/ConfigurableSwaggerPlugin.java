package com.github.rmannibucau.playx.swagger;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

import io.swagger.config.Scanner;
import io.swagger.config.ScannerFactory;
import io.swagger.config.SwaggerConfig;
import io.swagger.models.Path;
import io.swagger.models.Swagger;
import play.api.Application;
import play.api.inject.ApplicationLifecycle;
import play.api.routing.Router;
import play.modules.swagger.ApiListingCache;
import play.modules.swagger.ApiListingCache$;
import play.modules.swagger.PlayReader;
import play.modules.swagger.SwaggerPluginImpl;
import play.modules.swagger.util.SwaggerContext;
import scala.Option;

public class ConfigurableSwaggerPlugin extends SwaggerPluginImpl {

    @Inject
    public ConfigurableSwaggerPlugin(final ApplicationLifecycle lifecycle, final Router router, final Application app) {
        super(lifecycle, router, app);
        final Config config = app.configuration().underlying();
        final Map<String, ParsedConfig> customClasses = config.hasPath("swagger.api.additional")
                ? loadClasses(config.getObjectList("swagger.api.additional"))
                : emptyMap();
        if (!customClasses.isEmpty()) {
            final Swagger swagger = new EnrichedSwagger();
            routeScanning(swagger);
            ApiListingCache$.MODULE$.cache_$eq(
                    Option.apply(customClasses.entrySet().stream().collect(() -> swagger, this::enrichSwagger, (s1, s2) -> {
                        throw new IllegalStateException("unexpected merge");
                    })));
        } else {
            ApiListingCache$.MODULE$.cache_$eq(Option.apply(routeScanning(new Swagger())));
        }
        ApiListingCache.listing("", "127.0.0.1");
    }

    private Swagger routeScanning(final Swagger initSwagger) {
        final Scanner scanner = ScannerFactory.getScanner();
        final Swagger swagger = new PlayReader(initSwagger).read(scanner.classes());
        if (SwaggerConfig.class.isInstance(scanner)) {
            SwaggerConfig.class.cast(scanner).configure(swagger);
        }
        return swagger;
    }

    private void enrichSwagger(final Swagger swagger, final Map.Entry<String, ParsedConfig> config) {
        try {
            final Object reader = SwaggerContext.loadClass(config.getKey()).getConstructor(Swagger.class).newInstance(swagger);
            if (EnrichedSwagger.class.isInstance(swagger)) {
                EnrichedSwagger.class.cast(swagger).currentPrefix = config.getValue().prefix;
            }
            final Set<Class<?>> classes = config.getValue().classes.stream().map(SwaggerContext::loadClass).collect(toSet());
            final Method read = reader.getClass().getMethod("read", Set.class);
            read.invoke(reader, classes);
        } catch (final InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalArgumentException(e.getTargetException());
        }
    }

    private Map<String, ParsedConfig> loadClasses(final List<? extends ConfigObject> configs) {
        return configs.stream().collect(toMap(o -> String.class.cast(o.get("reader").unwrapped()),
                o -> new ParsedConfig(o.toConfig().hasPath("prefix") ? String.class.cast(o.get("prefix").unwrapped()) : "",
                        ConfigList.class.cast(o.get("classes")).stream().map(ConfigValue::unwrapped).map(String::valueOf)
                                .collect(toList()))));
    }

    private static class ParsedConfig {

        private final String prefix;

        private final Collection<String> classes;

        private ParsedConfig(final String prefix, final Collection<String> classes) {
            this.prefix = prefix;
            this.classes = classes;
        }
    }

    public static class EnrichedSwagger extends Swagger {

        @JsonIgnore
        private String currentPrefix = "";

        @Override
        public Path getPath(final String path) {
            return super.getPath(currentPrefix + path);
        }

        @Override
        public Swagger path(final String key, final Path path) {
            return super.path(currentPrefix + key, path);
        }
    }
}
