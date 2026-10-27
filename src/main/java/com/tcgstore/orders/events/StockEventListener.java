package com.tcgstore.orders.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tcgstore.orders.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orders' side of the saga: inventory answers order.created with stock.reserved or stock.failed.
 * Spring acks the message when this method returns and requeues it if it throws.
 */
@Component
public class StockEventListener {

	private static final Logger log = LoggerFactory.getLogger(StockEventListener.class);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record StockEvent(String eventId, String type, Data data) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Data(String orderId) {
		}

	}

	private final OrderService orders;

	private final JsonMapper json;

	public StockEventListener(OrderService orders, JsonMapper json) {
		this.orders = orders;
		this.json = json;
	}

	@RabbitListener(queues = RabbitConfig.QUEUE)
	public void onMessage(Message message) throws InterruptedException {
		StockEvent event = parse(message);
		try {
			switch (event.type()) {
				case "stock.reserved" -> orders.onStockReserved(event.data().orderId());
				case "stock.failed" -> orders.onStockFailed(event.data().orderId());
				default -> log.info("Ignoring event type {}", event.type());
			}
		}
		catch (RuntimeException e) {
			log.error("Failed to process {} {}, requeueing", event.type(), event.eventId(), e);
			Thread.sleep(1_000); // avoids a hot redelivery loop while PostgreSQL recovers
			throw e;
		}
	}

	// Malformed messages can never succeed, so they are dropped instead of requeued.
	private StockEvent parse(Message message) {
		try {
			StockEvent event = json.readValue(message.getBody(), StockEvent.class);
			if (event.type() == null || event.data() == null || event.data().orderId() == null
					|| event.data().orderId().isBlank()) {
				throw new AmqpRejectAndDontRequeueException("event without type or data.orderId");
			}
			return event;
		}
		catch (JacksonException e) {
			throw new AmqpRejectAndDontRequeueException("invalid JSON", e);
		}
	}

}
