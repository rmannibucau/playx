package com.github.rmannibucau.playx.demo.jaxrs;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

// note: in real app, use a dedicated pool, out of scope for this demo
@ApplicationScoped
@Path("/sample")
public class SampleEndpoint {

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public void getSample(@PathParam("id") final long id, @Suspended final AsyncResponse response) {
        new Thread(() -> response.resume(new Sample(id, "demo"))).start();
    }

    @POST
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public void postSample(@PathParam("id") final long id, final Sample incomingSample, @Suspended final AsyncResponse response) {
        new Thread(() -> response.resume(new Sample(id, incomingSample.getName()))).start();
    }

    public static class Sample {

        private long id;

        private String name;

        Sample(final long id, final String name) {
            this.id = id;
            this.name = name;
        }

        public Sample() {
            // no-op
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public long getId() {
            return id;
        }
    }
}
