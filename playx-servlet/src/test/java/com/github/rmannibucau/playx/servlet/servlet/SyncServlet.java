package com.github.rmannibucau.playx.servlet.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// @WebServlet(asyncSupported = true, urlPatterns = "/sync")
public class SyncServlet extends HttpServlet {

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        resp.getOutputStream().write("{\"source\":\"sync\"}".getBytes(StandardCharsets.UTF_8));
    }
}
