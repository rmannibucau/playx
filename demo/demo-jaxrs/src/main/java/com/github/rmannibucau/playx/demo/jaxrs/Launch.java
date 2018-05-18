package com.github.rmannibucau.playx.demo.jaxrs;

import static java.util.Collections.singletonMap;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.logging.Logger;

import org.apache.cxf.common.logging.Slf4jLogger;
import org.apache.webbeans.logger.WebBeansLoggerFactory;

import play.Application;
import play.ApplicationLoader;
import play.Environment;
import play.Mode;
import play.api.Play;
import play.core.server.Server;
import play.core.server.ServerConfig;
import play.core.server.ServerProvider;
import play.core.server.ServerProvider$;
import scala.Option;
import scala.compat.java8.OptionConverters;

// not for prod but nice in the IDE
public final class Launch {

    private Launch() {
        // no-op
    }

    public static void main(final String[] args) {
        // just to forward all logs to slf4j, mainly specific to this setup
        System.setProperty("org.apache.cxf.Logger", "org.apache.cxf.common.logging.Slf4jLogger");
        System.setProperty("openwebbeans.logging.factory", "com.github.rmannibucau.playx.demo.jaxrs.Launch$OWBLoggerFactory");

        // get the app
        final ApplicationLoader.Context context = ApplicationLoader.create(Environment.simple(),
                singletonMap("config.resource", "demo.conf"));
        final ApplicationLoader loader = ApplicationLoader.apply(context);
        final Application application = loader.load(context);

        // launch the server
        final ServerConfig serverConfig = ServerConfig.apply(application.classloader(), application.path(),
                OptionConverters.toScala(OptionalInt.of(Integer.getInteger("port", 8080))), Option.empty(), "0.0.0.0",
                Mode.TEST.asScala(), System.getProperties());
        final ServerProvider serverProvider = ServerProvider$.MODULE$.fromConfiguration(application.classloader(),
                application.asScala().configuration());
        final Server server = serverProvider.createServer(serverConfig, application.asScala());
        Play.start(application.asScala());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    public static class OWBLoggerFactory implements WebBeansLoggerFactory {

        @Override
        public Logger getLogger(final Class<?> aClass, final Locale locale) {
            return getLogger(aClass);
        }

        @Override
        public Logger getLogger(final Class<?> aClass) {
            return new Slf4jLogger(aClass.getName(), "openwebbeans/Messages");
        }
    }
}
