package com.quarkus.gatelink.notifications.boundary;

import io.smallrye.faulttolerance.api.RateLimitException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Returns an explicit HTTP 429 when the notification rate limit is exceeded. */
@Provider
public class RateLimitExceptionMapper implements ExceptionMapper<RateLimitException> {

    @Override
    public Response toResponse(RateLimitException exception) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.TEXT_PLAIN)
                .entity("Too many notification requests")
                .build();
    }
}
