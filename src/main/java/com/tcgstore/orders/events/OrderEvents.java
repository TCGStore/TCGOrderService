package com.tcgstore.orders.events;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.tcgstore.orders.order.OrderEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the order.* events of proto/events.md to the outbox. It must run inside the
 * transaction that changes the order, so the order and its event are saved together or not at all.
 */
@Component
public class OrderEvents {

	private final OutboxEventRepository outbox;

	private final JsonMapper json;

	private final Clock clock;

	public OrderEvents(OutboxEventRepository outbox, JsonMapper json, Clock clock) {
		this.outbox = outbox;
		this.json = json;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void orderCreated(OrderEntity order) {
		var items = order.getItems()
			.stream()
			.map(item -> Map.of("productId", item.getProductId(), "name", item.getName(), "quantity",
					item.getQuantity(), "unitPriceCents", item.getUnitPriceCents()))
			.toList();
		var data = customerData(order);
		data.put("items", items);
		data.put("totalCents", order.getTotalCents());
		save("order.created", data);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void orderConfirmed(OrderEntity order) {
		var data = customerData(order);
		data.put("totalCents", order.getTotalCents());
		save("order.confirmed", data);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void orderCancelled(OrderEntity order) {
		var data = customerData(order);
		data.put("reason", order.getCancellationReason().name());
		save("order.cancelled", data);
	}

	private static Map<String, Object> customerData(OrderEntity order) {
		var data = new LinkedHashMap<String, Object>();
		data.put("orderId", order.getId());
		data.put("userId", order.getUserId());
		data.put("userEmail", order.getUserEmail());
		return data;
	}

	private void save(String type, Map<String, Object> data) {
		UUID eventId = UUID.randomUUID();
		Instant now = clock.instant();
		var envelope = new LinkedHashMap<String, Object>();
		envelope.put("eventId", eventId.toString());
		envelope.put("type", type);
		envelope.put("occurredAt", now.truncatedTo(ChronoUnit.SECONDS).toString());
		envelope.put("data", data);
		outbox.save(new OutboxEventEntity(eventId, type, json.writeValueAsString(envelope), now));
	}

}
