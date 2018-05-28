package com.github.rmannibucau.playx.swagger;

import static java.util.Arrays.asList;

import java.util.List;

import controllers.ApiHelpController;
import io.swagger.models.Swagger;
import play.api.Configuration;
import play.api.Environment;
import play.api.inject.Binding;
import play.api.inject.Module;
import play.modules.swagger.ApiListingCache$;
import play.modules.swagger.SwaggerPlugin;
import scala.Some;
import scala.collection.JavaConverters;
import scala.collection.Seq;

/**
 * Replacement for play.modules.swagger.SwaggerModule.
 */
public class ConfigurableSwaggerModule extends Module {

    @Override
    public Seq<Binding<?>> bindings(final Environment environment, final Configuration configuration) {
        ApiListingCache$.MODULE$.cache_$eq(Some.apply(new Swagger())); // avoid to scan twice later
        final List<Binding<?>> bindings = asList(
                bind(SwaggerPlugin.class).to(ConfigurableSwaggerPlugin.class).eagerly(),
                bind(ApiHelpController.class).toSelf().eagerly());
        return JavaConverters.asScalaBuffer(bindings);
    }
}
