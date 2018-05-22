package com.github.rmannibucau.playx.servlet.servlet.internal;

import java.io.IOException;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class FilterChainImpl implements FilterChain {
    private final List<DynamicFilter> filters;
    private final DynamicServlet servlet;
    private int index = 0;

    public FilterChainImpl(final List<DynamicFilter> filters, final DynamicServlet servlet) {
        this.filters = filters;
        this.servlet = servlet;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response) throws IOException, ServletException {
        if (filters.size() == index) {
            servlet.getInstance().service(request, response);
        } else {
            filters.get(index).getInstance().doFilter(request, response, this);
        }
        index++;
    }
}
