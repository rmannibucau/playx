package com.github.rmannibucau.playx.servlet.servlet.internal;

import static java.util.Arrays.asList;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;

public class DynamicFilter implements FilterRegistration.Dynamic {

    private final String name;

    private final Filter instance;

    private final Collection<String> mappings = new HashSet<>();

    private final Collection<String> servletNames = new HashSet<>();

    private final Map<String, String> initParameters = new HashMap<>();

    private boolean asyncSupported;

    public DynamicFilter(final String name, final Filter filter) {
        this.name = name;
        this.instance = filter;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public Filter getInstance() {
        return instance;
    }

    @Override
    public void setAsyncSupported(final boolean isAsyncSupported) {
        asyncSupported = isAsyncSupported;
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

    @Override
    public void addMappingForServletNames(final EnumSet<DispatcherType> dispatcherTypes, final boolean isMatchAfter,
            final String... servletNames) {
        this.servletNames.addAll(asList(servletNames));
    }

    @Override
    public Collection<String> getServletNameMappings() {
        return servletNames;
    }

    @Override
    public void addMappingForUrlPatterns(final EnumSet<DispatcherType> dispatcherTypes, final boolean isMatchAfter,
            final String... urlPatterns) {
        mappings.addAll(asList(urlPatterns));
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
        return mappings;
    }

    public FilterConfig toFilterConfig(final ServletContext context) {
        return new FilterConfig() {

            @Override
            public String getFilterName() {
                return name;
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
        return "DynamicFilter(" + name + ')';
    }
}
