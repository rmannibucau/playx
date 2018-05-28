package com.github.rmannibucau.playx.swagger;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static play.test.Helpers.running;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import play.Application;
import play.api.inject.guice.GuiceApplicationBuilder;
import play.api.inject.guice.GuiceableModule;
import play.api.inject.guice.GuiceableModule$;
import play.api.routing.Router$;
import play.modules.swagger.ApiListingCache$;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map;

public class ConfigurableSwaggerPluginTest {

    private static Application application;

    @ClassRule
    public static final TestRule play = (base, description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            final scala.collection.immutable.Map<String, Object> classes = new Map.Map1<>("swagger.api.additional",
                    singletonList(new HashMap<String, Object>() {

                        {
                            put("reader", Reader.class.getName());
                            put("classes", singletonList(MyCustomClass.class.getName()));
                            put("prefix", "/api");
                        }
                    }));
            final GuiceableModule swaggerModule = GuiceableModule$.MODULE$.fromPlayModule(new ConfigurableSwaggerModule());
            application = new GuiceApplicationBuilder().router(Router$.MODULE$.empty()).configure(classes)
                    .bindings(JavaConverters.asScalaBuffer(singletonList(swaggerModule)).toSeq()).build().asJava();
            final AtomicReference<Throwable> error = new AtomicReference<>();
            try {
                running(application, () -> {
                    try {
                        base.evaluate();
                    } catch (final Throwable throwable) {
                        error.set(throwable);
                    }
                });
            } finally {
                application = null;
            }
            if (error.get() != null) {
                throw error.get();
            }
        }
    };

    @Api("/custom")
    @Path("custom")
    public static class MyCustomClass {

        @GET
        @ApiOperation("testOne")
        public String get() {
            return null;
        }
    }

    @Test
    public void swagger() {
        final Swagger swagger = ApiListingCache$.MODULE$.cache().get();
        assertNotNull(swagger);
        assertEquals(1, swagger.getPaths().size());
        assertEquals("testOne", swagger.getPaths().get("/api/custom").getGet().getSummary());
    }
}
