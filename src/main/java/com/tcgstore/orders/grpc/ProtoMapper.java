package com.tcgstore.orders.grpc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.tcgstore.orders.cart.CartService.CartLine;
import com.tcgstore.orders.order.OrderEntity;
import com.tcgstore.orders.order.OrderItemEntity;
import com.tcgstore.orders.order.ShippingAddress;

final class ProtoMapper {

	private ProtoMapper() {
	}

	static Cart cart(String userId, List<CartLine> lines) {
		var cart = Cart.newBuilder().setUserId(userId);
		for (CartLine line : lines) {
			cart.addItems(CartItem.newBuilder()
				.setProductId(line.productId())
				.setName(line.name())
				.setUnitPriceCents(line.unitPriceCents())
				.setQuantity(line.quantity())
				.setSubtotalCents(line.subtotalCents()));
		}
		return cart.setTotalCents(lines.stream().mapToLong(CartLine::subtotalCents).sum()).build();
	}

	static Order order(OrderEntity entity) {
		var order = Order.newBuilder()
			.setId(entity.getId())
			.setUserId(entity.getUserId())
			.setStatus(switch (entity.getStatus()) {
				case PENDING -> OrderStatus.ORDER_STATUS_PENDING;
				case CONFIRMED -> OrderStatus.ORDER_STATUS_CONFIRMED;
				case CANCELLED -> OrderStatus.ORDER_STATUS_CANCELLED;
			})
			.setTotalCents(entity.getTotalCents())
			.setShippingAddress(address(entity.getShippingAddress()))
			.setCreatedAt(rfc3339(entity.getCreatedAt()))
			.setUpdatedAt(rfc3339(entity.getUpdatedAt()));
		if (entity.getCancellationReason() != null) {
			order.setCancellationReason(switch (entity.getCancellationReason()) {
				case OUT_OF_STOCK -> CancellationReason.CANCELLATION_REASON_OUT_OF_STOCK;
				case PAYMENT_DECLINED -> CancellationReason.CANCELLATION_REASON_PAYMENT_DECLINED;
			});
		}
		for (OrderItemEntity item : entity.getItems()) {
			order.addItems(OrderItem.newBuilder()
				.setProductId(item.getProductId())
				.setName(item.getName())
				.setUnitPriceCents(item.getUnitPriceCents())
				.setQuantity(item.getQuantity())
				.setSubtotalCents(item.getSubtotalCents()));
		}
		return order.build();
	}

	/** Returns null when the request has no address, so the service can report it. */
	static ShippingAddress shippingAddress(boolean present, Address address) {
		if (!present) {
			return null;
		}
		return new ShippingAddress(address.getRecipientName().trim(), address.getStreet().trim(),
				address.getCity().trim(), address.getRegion().trim(), address.getPostalCode().trim(),
				address.getCountry().trim());
	}

	private static Address address(ShippingAddress address) {
		return Address.newBuilder()
			.setRecipientName(address.recipientName())
			.setStreet(address.street())
			.setCity(address.city())
			.setRegion(address.region())
			.setPostalCode(address.postalCode())
			.setCountry(address.country())
			.build();
	}

	private static String rfc3339(Instant instant) {
		return instant.truncatedTo(ChronoUnit.SECONDS).toString();
	}

}
