package com.github.rmannibucau.playx.ioc;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static play.test.Helpers.running;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

import play.Application;
import play.ApplicationLoader;
import play.Environment;
import play.Mode;
import play.api.inject.BindingKey;
import play.inject.Injector;
import play.inject.guice.GuiceApplicationLoader;

import com.typesafe.config.Config;

public class IoCTest {

    private static Application app;

    @ClassRule
    public static final TestRule play = (base, description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            final ApplicationLoader.Context context = ApplicationLoader.create(
                    new Environment(new File("target/play"), Thread.currentThread().getContextClassLoader(), Mode.TEST),
                    new HashMap<String, Object>() {

                        {
                            put("play.application.loader", IoCLoader.class.getName());
                            put("playx.ioc.loaders", asList(
                                    GuiceApplicationLoader.class.getName(),
                                    CustomLoader.class.getName()));
                        }
                    });
            final ApplicationLoader loader = ApplicationLoader.apply(context);
            assertThat(getField(getField(loader, "val$loader"), "javaApplicationLoader$1"), instanceOf(IoCLoader.class));
            app = loader.load(context);
            final AtomicReference<Throwable> error = new AtomicReference<>();
            try {
                running(app, () -> {
                    try {
                        base.evaluate();
                    } catch (final Throwable throwable) {
                        error.set(throwable);
                    }
                });
            } finally {
                app = null;
            }
            if (error.get() != null) {
                throw error.get();
            }
        }
    };

    @Test
    public void checkApplication() {
        assertNotNull(app);

        final Injector injector = app.injector();
        assertEquals("com.github.rmannibucau.playx.ioc.IoCLoader$IoCInjector", injector.getClass().getName());

        checkInjector(injector);
    }

    private void checkInjector(final Injector injector) {
        Stream.of(Config.class, Application.class, play.api.Application.class,
                SpecificApi.class)
              .forEach(clazz -> assertNotNull(clazz.getName(), injector.instanceOf(clazz)));
    }

    private static Object getField(final Object root, final String field) {
        Class<?> clazz = root.getClass();
        while (clazz != null) {
            try {
                final Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(root);
            } catch (final Exception e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalStateException("No field " + field + " in " + root);
    }

    public interface SpecificApi {}

    public static class CustomLoader implements ApplicationLoader {
        @Override
        public Application load(final Context context) {
            return new Application() {
                @Override
                public play.api.Application getWrappedApplication() {
                    return null;
                }

                @Override
                public play.api.Application asScala() {
                    return null;
                }

                @Override
                public Config config() {
                    return null;
                }

                @Override
                public Injector injector() {
                    return new Injector() {
                        @Override
                        public <T> T instanceOf(final Class<T> clazz) {
                            if (clazz == SpecificApi.class) {
                                return clazz.cast(new SpecificApi() {});
                            }
                            return null;
                        }

                        @Override
                        public <T> T instanceOf(final BindingKey<T> key) {
                            return instanceOf(key.clazz());
                        }

                        @Override
                        public play.api.inject.Injector asScala() {
                            return null;
                        }
                    };
                }
            };
        }
    }
}
