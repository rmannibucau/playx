package com.github.rmannibucau.playx.microprofile.config;

import static org.junit.Assert.assertEquals;

import javax.enterprise.inject.Produces;
import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.Test;

import com.typesafe.config.Config;

import play.api.Configuration;
import scala.collection.immutable.Map;

public class TypesafeConfigConfigSourceTest {

    @Test
    public void test() {
        try (final SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(MyConf.class, ConfProvider.class).initialize()) {
            assertEquals("http://foo.com", container.select(MyConf.class).get().getUrl());
        }
    }

    public static class ConfProvider {

        @Produces
        public Config config() {
            return Configuration.from(new Map.Map1<>("app.url", "http://foo.com")).underlying();
        }
    }

    public static class MyConf {

        @Inject
        @ConfigProperty(name = "app.url")
        private String url;

        public String getUrl() {
            return url;
        }
    }
}
