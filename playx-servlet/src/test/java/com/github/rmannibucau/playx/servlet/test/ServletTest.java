package com.github.rmannibucau.playx.servlet.test;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static play.test.Helpers.running;
import static play.test.Helpers.testServer;

import play.inject.guice.GuiceApplicationBuilder;
import play.test.TestServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

import com.github.rmannibucau.playx.servlet.servlet.api.ServletFilter;
import com.github.rmannibucau.playx.servlet.setup.ServletSetup;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

public class ServletTest {

    private static TestServer server;

    @ClassRule
    public static final TestRule play = (base, description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            server = testServer(new GuiceApplicationBuilder()
                    .configure("playx.servlet.initializers", singletonList(ServletSetup.class.getName()))
                    .configure("play.filters.enabled.100", ServletFilter.class.getName()).build());
            final AtomicReference<Throwable> error = new AtomicReference<>();
            try {
                running(server, () -> {
                    try {
                        base.evaluate();
                    } catch (final Throwable throwable) {
                        error.set(throwable);
                    }
                });
            } finally {
                server = null;
            }
            if (error.get() != null) {
                throw error.get();
            }
        }
    };

    @Test
    public void request() {
        doTest("/request", "uri=/request\nurl=http://localhost:" + server.port() + "/request\ncontext=\n"
                + "servlet=/request\npathinfo=");
    }

    @Test
    public void star() {
        doTest("/star/async/test", "{\"text\":\"/star/async/test\"}");
    }

    @Test
    public void async() {
        doTest("/async", "{\"text\":\"ok\"}");
    }

    @Test
    public void dispatch() {
        doTest("/asyncdispatch", "{\"source\":\"dispatch\"}");
    }

    @Test
    public void sync() {
        doTest("/sync", "{\"source\":\"sync\"}");
    }

    private void doTest(final String endpoint, final String expected) {
        try {
            final URL url = new URL(String.format("http://localhost:%d%s", server.port(), endpoint));
            try (final BufferedReader stream = new BufferedReader(new InputStreamReader(url.openStream()))) {
                final String output = stream.lines().collect(joining("\n"));
                assertEquals(expected, output);
            }
        } catch (final IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
}
