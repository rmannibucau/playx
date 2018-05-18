package com.github.rmannibucau.playx.servlet.servlet.api;

import akka.util.ByteString;
import play.http.HttpErrorHandler;
import play.inject.Injector;
import play.libs.streams.Accumulator;
import play.mvc.BodyParser;
import play.mvc.EssentialAction;
import play.mvc.EssentialFilter;
import play.mvc.Http;
import play.mvc.Result;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

// the implementation is "lazy" to ensure it can be compatible with IoCLoader
@Singleton
public class ServletFilter extends EssentialFilter implements Consumer<ServletFilter> {
    private Injector injector;
    private ServletFilter self;
    private final State state = new State();

    @Inject
    public ServletFilter(final Injector injector) {
        this.injector = injector;
    }

    @Override
    public EssentialAction apply(final EssentialAction next) {
        state.ensureInit(injector);
        return new EssentialAction() {

            @Override
            public Accumulator<ByteString, Result> apply(final Http.RequestHeader requestHeader) {
                return state.getServletContext().findMatchingServlet(requestHeader).map(servlet -> {
                    final long length = requestHeader.getHeaders().get("Content-Length").map(Long::parseLong)
                            .orElse(Long.MAX_VALUE);
                    final BodyParser.Bytes slurper = new BodyParser.Bytes(length, state.getHttpErrorHandler());
                    return slurper.apply(requestHeader).mapFuture(
                            resultOrBytes -> resultOrBytes.left.map(CompletableFuture::completedFuture).orElseGet(() -> {
                                return state.getServletContext()
                                        .executeInvoke(servlet.getDynamicServlet(), requestHeader,
                                                resultOrBytes.right.get().iterator().asInputStream(), servlet.getServletPath())
                                        .toCompletableFuture();
                            }), state.getServletContext().getDefaultExecutor());
                }).orElseGet(() -> next.apply(requestHeader));
            }
        };
    }

    @Override
    public void accept(final ServletFilter actual) {
        this.state.ensureInit(actual.getInjector());
    }

    Injector getInjector() {
        return injector;
    }

    @Singleton
    public static class State {
        @Inject
        private PlayServletContext servletContext;

        @Inject
        private HttpErrorHandler httpErrorHandler;

        private volatile boolean init = false;

        public PlayServletContext getServletContext() {
            return servletContext;
        }

        public HttpErrorHandler getHttpErrorHandler() {
            return httpErrorHandler;
        }

        void ensureInit(final Injector injector) {
            if (!init) {
                synchronized (this) {
                    if (!init) {
                        servletContext = injector.instanceOf(PlayServletContext.class);
                        httpErrorHandler = injector.instanceOf(HttpErrorHandler.class);
                    }
                    init = true;
                }
            }
        }
    }
}
