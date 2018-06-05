package com.github.rmannibucau.playx.microprofile.config;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;

import java.util.HashMap;
import java.util.Map;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;

import org.eclipse.microprofile.config.spi.ConfigSource;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

import play.api.Environment;

public class TypesafeConfigConfigSource implements ConfigSource {

    private volatile Map<String, String> configuration;

    @Override
    public Map<String, String> getProperties() {
        if (configuration == null) {
            synchronized (this) {
                if (configuration == null) {
                    final Map<String, String> aggregator = new HashMap<>();

                    try {
                        final CDI<Object> current = CDI.current();
                        final Instance<Config> configs = current.select(Config.class);
                        if (configs.isResolvable()) {
                            final Config config = configs.get();
                            visit(aggregator, config, "");
                        }

                        final Instance<Environment> environment = current.select(Environment.class);
                        if (environment.isResolvable()) {
                            final Environment env = environment.get();
                            aggregator.put("playx.application.mode", env.mode().asJava().name());
                            aggregator.put("playx.application.home", env.rootPath().getAbsolutePath());
                        }
                    } catch (final RuntimeException re) {
                        return emptyMap(); // server not yet started or ready, retry later
                    }

                    configuration = aggregator;
                }
            }
        }
        return configuration;
    }

    @Override
    public String getValue(final String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return "cdi-typesafe-config";
    }

    private void visit(final Map<String, String> aggregator, final Config config, final String prefix) {
        config.entrySet().forEach(e -> {
            final ConfigValue value = e.getValue();
            final String currentKey = prefix + (prefix.isEmpty() ? "" : ".") + e.getKey();
            switch (value.valueType()) {
            case OBJECT:
                visit(aggregator, ConfigObject.class.cast(value).toConfig(), currentKey);
                break;
            case LIST: // only for primitives for now
                aggregator.put(currentKey, ConfigList.class.cast(value).stream().map(v -> String.valueOf(v.unwrapped())).collect(joining(",")));
                break;
            case NULL:
                break;
            case NUMBER:
            case BOOLEAN:
            case STRING:
            default:
                aggregator.put(currentKey, String.valueOf(value.unwrapped()));
                break;
            }
        });
    }
}
