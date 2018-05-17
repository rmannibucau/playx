package com.github.rmannibucau.playx.servlet.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// assumed to do like cxf and @Suspended support
// @WebServlet(asyncSupported = true, urlPatterns = "/asyncdispatch")
public class AsyncDispatchServlet extends HttpServlet {

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
        final Object answer = req.getAttribute("answer");
        if (answer != null) {
            try {
                resp.getOutputStream().write(answer.toString().getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } finally {
                AsyncContext.class.cast(req.getAttribute("context")).complete();
            }
        } else {
            final AsyncContext asyncContext = req.startAsync();
            new Thread(() -> {
                req.setAttribute("answer", "{\"source\":\"dispatch\"}");
                req.setAttribute("context", asyncContext);
                try {
                    asyncContext.dispatch();
                } catch (final RuntimeException e) {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    asyncContext.complete();
                }
            }).start();
        }
    }
}
