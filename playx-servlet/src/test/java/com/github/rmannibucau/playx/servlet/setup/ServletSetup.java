package com.github.rmannibucau.playx.servlet.setup;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;

import com.github.rmannibucau.playx.servlet.servlet.AsyncDispatchServlet;
import com.github.rmannibucau.playx.servlet.servlet.AsyncServlet;
import com.github.rmannibucau.playx.servlet.servlet.RequestDataServlet;
import com.github.rmannibucau.playx.servlet.servlet.SyncServlet;


public class ServletSetup implements ServletContainerInitializer {

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
            final ServletRegistration.Dynamic servlet = servletContext.addServlet("request", new RequestDataServlet());
            servlet.addMapping("/request");
            servlet.setAsyncSupported(true);
        }
        {
            servletContext.addServlet("sync", new SyncServlet()).addMapping("/sync");
        }
    }
}
