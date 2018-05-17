package com.github.rmannibucau.playx.servlet.setup;

import static java.util.Collections.singleton;

import java.util.Collection;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;

import com.github.rmannibucau.playx.servlet.servlet.AsyncDispatchServlet;
import com.github.rmannibucau.playx.servlet.servlet.AsyncServlet;
import com.github.rmannibucau.playx.servlet.servlet.SyncServlet;
import com.github.rmannibucau.playx.servlet.servlet.api.PlayServletContext;
import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;

public class ServletModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(PlayServletContext.class);
        // this way we can support a list of initializers
        bind(new TypeLiteral<Collection<ServletContainerInitializer>>() {}).toInstance(singleton(new Setup()));
    }

    public static class Setup implements ServletContainerInitializer {

        @Override
        public void onStartup(final Set<Class<?>> c, final ServletContext servletContext) {
            {
                final ServletRegistration.Dynamic servlet = servletContext.addServlet("async", new AsyncServlet());
                servlet.addMapping("/async");
                servlet.setAsyncSupported(true);
            }
            {
                final ServletRegistration.Dynamic servlet = servletContext.addServlet("async2", new AsyncServlet());
                servlet.addMapping("/star/async/*");
                servlet.setAsyncSupported(true);
            }
            {
                final ServletRegistration.Dynamic servlet = servletContext.addServlet("asyncdispatch",
                        new AsyncDispatchServlet());
                servlet.addMapping("/asyncdispatch");
                servlet.setAsyncSupported(true);
            }
            {
                servletContext.addServlet("sync", new SyncServlet()).addMapping("/sync");
            }
        }
    }
}
