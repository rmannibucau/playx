package com.github.rmannibucau.playx.cdi;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static play.test.Helpers.running;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

import com.github.rmannibucau.playx.cdi.bean.MyService;

import play.Application;
import play.ApplicationLoader;
import play.Environment;
import play.Mode;
import play.inject.Injector;

import javax.enterprise.inject.Vetoed;

public class CdiLoaderTest {

    private static Application app;

    @ClassRule
    public static final TestRule play = (base, description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            final ApplicationLoader.Context context = ApplicationLoader.create(
                    new Environment(new File("target/play"), Thread.currentThread().getContextClassLoader(), Mode.TEST),
                    new HashMap<String, Object>() {

                        {
                            put("play.application.loader", CdiLoader.class.getName());
                            put("playx.cdi.beans.customs", singletonList(singletonMap("className", MyConfiguredBean.class.getName())));
                        }
                    });
            final ApplicationLoader loader = ApplicationLoader.apply(context);
            assertThat(getField(getField(loader, "val$loader"), "javaApplicationLoader$1"), instanceOf(CdiLoader.class));
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
        assertEquals("com.github.rmannibucau.playx.cdi.CdiLoader$CdiInjector", injector.getClass().getName());
        assertEquals("ok", injector.instanceOf(MyService.class).test());
    }

    @Test
    public void checkCustombeans() {
        final Injector injector = app.injector();
        assertEquals("called", injector.instanceOf(MyConfiguredBean.class).call());
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

    @Vetoed
    public static class MyConfiguredBean {
        public String call() {
            return "called";
        }
    }
}
