package com.github.rmannibucau.playx.servlet.servlet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// @WebServlet(asyncSupported = true, urlPatterns = "/async")
public class AsyncServlet extends HttpServlet {

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
        final AsyncContext asyncContext = req.startAsync();
        new Thread(() -> {
            try {
                if (req.getRequestURI().contains("/star")) {
                    resp.getOutputStream().write(("{\"text\":\"" + req.getRequestURI() + "\"}").getBytes(StandardCharsets.UTF_8));
                } else {
                    resp.getOutputStream().write("{\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                }
            } catch (final IOException e) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                try {
                    e.printStackTrace(new PrintStream(resp.getOutputStream()));
                } catch (final IOException e1) {
                    throw new IllegalStateException(e1);
                }
            } finally {
                asyncContext.complete();
            }
        }).start();
    }
}
