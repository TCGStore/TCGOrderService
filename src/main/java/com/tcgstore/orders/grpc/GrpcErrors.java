package com.tcgstore.orders.grpc;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * Services throw {@link StatusRuntimeException} with the codes from proto/README.md;
 * anything else is a bug and reaches the client as INTERNAL without internal details.
 */
@Component
public class GrpcErrors implements GrpcExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GrpcErrors.class);

	public static StatusRuntimeException invalidArgument(String message) {
		return Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException();
	}

	public static StatusRuntimeException notFound(String message) {
		return Status.NOT_FOUND.withDescription(message).asRuntimeException();
	}

	public static StatusRuntimeException failedPrecondition(String message) {
		return Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException();
	}

	@Override
	public StatusException handleException(Throwable error) {
		if (error instanceof StatusRuntimeException status) {
			return status.getStatus().asException(status.getTrailers());
		}
		log.error("Unexpected error in gRPC call", error);
		return Status.INTERNAL.withDescription("internal error").asException();
	}

}
