package com.github.rmannibucau.playx.servlet.servlet.internal;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import com.github.rmannibucau.playx.servlet.servlet.api.PlayServletContext;

public class RequestDispatcherImpl implements RequestDispatcher {

    private final PlayServletContext context;

    private final String path;

    public RequestDispatcherImpl(final PlayServletContext context, final String path) {
        this.context = context;
        this.path = path;
    }

    private void doExecute(final ServletRequest request, final ServletResponse response) {
        context.getDefaultExecutor().execute(() -> {
            final DynamicServlet dynamicServlet = context.findFirstMatchingServlet(path)
                    .orElseThrow(() -> new IllegalArgumentException("No matching servlet for path '" + path + "'"));
            try {
                dynamicServlet.getInstance().service(request, response);
            } catch (final ServletException | IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void forward(final ServletRequest request, final ServletResponse response) {
        doExecute(request, response);
    }

    @Override
    public void include(final ServletRequest request, final ServletResponse response) {
        doExecute(request, response);
    }
}
