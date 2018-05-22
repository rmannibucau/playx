package com.github.rmannibucau.playx.microprofile.config;

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

public class TypesafeConfigConfigSource implements ConfigSource {

    private volatile Map<String, String> configuration;

    @Override
    public Map<String, String> getProperties() {
        if (configuration == null) {
            synchronized (this) {
                if (configuration == null) {
                    final Map<String, String> aggregator = new HashMap<>();

                    final Instance<Config> selected = CDI.current().select(Config.class);
                    if (selected.isResolvable()) {
                        final Config config = selected.get();
                        visit(aggregator, config, "");
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
