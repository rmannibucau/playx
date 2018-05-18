package com.github.rmannibucau.playx.servlet.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RequestDataServlet extends HttpServlet {

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final PrintWriter writer = resp.getWriter();
        writer.println("uri=" + req.getRequestURI());
        writer.println("url=" + req.getRequestURL().toString());
        writer.println("context=" + req.getContextPath());
        writer.println("servlet=" + req.getServletPath());
        writer.println("pathinfo=" + req.getPathInfo());
    }
}
