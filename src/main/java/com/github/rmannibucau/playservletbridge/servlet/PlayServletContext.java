package com.github.rmannibucau.playservletbridge.servlet;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;

public class PlayServletContext implements ServletContext {

    private static final Logger LOGGER = Logger.getLogger(PlayServletContext.class.getName());

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public ServletContext getContext(final String uripath) {
        return this;
    }

    @Override
    public int getMajorVersion() {
        return 4;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 4;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getMimeType(final String file) {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(final String path) {
        return emptySet();
    }

    @Override
    public URL getResource(final String path) {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(final String path) {
        return null;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String path) {
        return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(final String name) {
        return null;
    }

    @Override
    public Servlet getServlet(final String name) {
        return null;
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        return emptyEnumeration();
    }

    @Override
    public Enumeration<String> getServletNames() {
        return emptyEnumeration();
    }

    @Override
    public void log(final String msg) {
        LOGGER.info(msg);
    }

    @Override
    public void log(final Exception exception, final String msg) {
        LOGGER.log(Level.SEVERE, msg, exception);
    }

    @Override
    public void log(final String message, final Throwable throwable) {
        LOGGER.log(Level.SEVERE, message, throwable);
    }

    @Override
    public String getRealPath(final String path) {
        return null;
    }

    @Override
    public String getServerInfo() {
        return "Play-Servlet-Bridge/1.0";
    }

    @Override
    public String getInitParameter(final String name) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return emptyEnumeration();
    }

    @Override
    public boolean setInitParameter(final String name, final String value) {
        return false;
    }

    @Override
    public Object getAttribute(final String name) {
        return null;
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return emptyEnumeration();
    }

    @Override
    public void setAttribute(final String name, final Object object) {
        // no-op
    }

    @Override
    public void removeAttribute(final String name) {
        // no-op
    }

    @Override
    public String getServletContextName() {
        return "";
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final Servlet servlet) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(final String servletName, final Class<? extends Servlet> servletClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(final String jspName, final String jspFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Servlet> T createServlet(final Class<T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration getServletRegistration(final String servletName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return emptyMap();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Filter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(final String filterName, final Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Filter> T createFilter(final Class<T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration getFilterRegistration(final String filterName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTrackingModes(final Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return emptySet();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return emptySet();
    }

    @Override
    public void addListener(final String className) {
        // no-op
    }

    @Override
    public <T extends EventListener> void addListener(final T t) {
        // no-op
    }

    @Override
    public void addListener(final Class<? extends EventListener> listenerClass) {
        // no-op
    }

    @Override
    public <T extends EventListener> T createListener(final Class<T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void declareRoles(final String... roleNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVirtualServerName() {
        return "localhost";
    }

    @Override
    public int getSessionTimeout() {
        return 30;
    }

    @Override
    public void setSessionTimeout(final int sessionTimeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestCharacterEncoding() {
        return StandardCharsets.ISO_8859_1.name();
    }

    @Override
    public void setRequestCharacterEncoding(final String encoding) {
        // no-op
    }

    @Override
    public String getResponseCharacterEncoding() {
        return StandardCharsets.ISO_8859_1.name();
    }

    @Override
    public void setResponseCharacterEncoding(final String encoding) {
        // no-op
    }
}
