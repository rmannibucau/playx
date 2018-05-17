package com.github.rmannibucau.playx.servlet.servlet.internal;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;

public class DynamicServlet implements ServletRegistration.Dynamic {

    private final String name;

    private final Servlet instance;

    private int loadOnStartup = -1;

    private final Collection<String> mappings = new HashSet<>();

    private final Map<String, String> initParameters = new HashMap<>();

    private boolean asyncSupported;

    public DynamicServlet(final String name, final Servlet servlet) {
        this.name = name;
        this.instance = servlet;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public Servlet getInstance() {
        return instance;
    }

    public int getLoadOnStartup() {
        return loadOnStartup;
    }

    @Override
    public void setLoadOnStartup(final int loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    @Override
    public Set<String> setServletSecurity(final ServletSecurityElement constraint) {
        return emptySet();
    }

    @Override
    public void setMultipartConfig(final MultipartConfigElement multipartConfig) {
        // no-op
    }

    @Override
    public void setRunAsRole(final String roleName) {
        // no-op
    }

    @Override
    public void setAsyncSupported(final boolean isAsyncSupported) {
        asyncSupported = isAsyncSupported;
    }

    @Override
    public Set<String> addMapping(final String... urlPatterns) {
        mappings.addAll(asList(urlPatterns));
        return Stream.of(urlPatterns).collect(toSet());
    }

    @Override
    public Collection<String> getMappings() {
        return mappings;
    }

    @Override
    public String getRunAsRole() {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getClassName() {
        return instance.getClass().getName();
    }

    @Override
    public boolean setInitParameter(final String name, final String value) {
        initParameters.put(name, value);
        return false;
    }

    @Override
    public String getInitParameter(final String name) {
        return initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(final Map<String, String> initParameters) {
        this.initParameters.putAll(initParameters);
        return initParameters.keySet();
    }

    @Override
    public Map<String, String> getInitParameters() {
        return initParameters;
    }

    public ServletConfig toServletConfig(final ServletContext context) {
        return new ServletConfig() {

            @Override
            public String getServletName() {
                return DynamicServlet.this.getName();
            }

            @Override
            public ServletContext getServletContext() {
                return context;
            }

            @Override
            public String getInitParameter(final String name) {
                return initParameters.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(initParameters.keySet());
            }
        };
    }

    @Override
    public String toString() {
        return "DynamicServlet(" + name + ')';
    }
}
