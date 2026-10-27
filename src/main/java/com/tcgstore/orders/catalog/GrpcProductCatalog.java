package com.tcgstore.orders.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.tcgstore.catalog.grpc.CatalogServiceGrpc;
import com.tcgstore.catalog.grpc.GetProductsRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcProductCatalog implements ProductCatalog {

	private final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;

	// "catalog" is the channel configured under spring.grpc.client.channel.catalog.*
	public GrpcProductCatalog(GrpcChannelFactory channels) {
		this.catalog = CatalogServiceGrpc.newBlockingStub(channels.createChannel("catalog"));
	}

	@Override
	public Map<String, CatalogProduct> findByIds(Collection<String> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		try {
			return catalog.getProducts(GetProductsRequest.newBuilder().addAllIds(ids).build())
				.getProductsList()
				.stream()
				.map(p -> new CatalogProduct(p.getId(), p.getName(), p.getPriceCents()))
				.collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
		}
		catch (StatusRuntimeException e) {
			// Without this, our caller would see catalog's error as if it came from orders-service.
			throw Status.UNAVAILABLE.withDescription("catalog-service: " + e.getStatus().getCode())
				.withCause(e)
				.asRuntimeException();
		}
	}

}
