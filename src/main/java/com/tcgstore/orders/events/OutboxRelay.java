package com.tcgstore.orders.events;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends outbox events to RabbitMQ. If the service dies after sending but before marking them,
 * they go out again with the same eventId, which consumers already treat as a duplicate.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final int BATCH_SIZE = 50;

	private static final long CONFIRM_TIMEOUT_MS = 5_000;

	private final OutboxEventRepository outbox;

	private final RabbitTemplate rabbit;

	private final Clock clock;

	public OutboxRelay(OutboxEventRepository outbox, RabbitTemplate rabbit, Clock clock) {
		this.outbox = outbox;
		this.rabbit = rabbit;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${orders.outbox.poll-interval}")
	@Transactional
	public void publishPending() {
		List<OutboxEventEntity> pending = outbox.lockPending(BATCH_SIZE);
		if (pending.isEmpty()) {
			return;
		}

		// Waits for RabbitMQ to confirm it stored the messages before marking them as sent.
		rabbit.invoke(channel -> {
			for (OutboxEventEntity event : pending) {
				channel.send(RabbitConfig.EXCHANGE, event.getRoutingKey(), toMessage(event));
			}
			channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
			return null;
		});

		pending.forEach(event -> {
			event.markPublished(clock.instant());
			log.info("Published {} {}", event.getRoutingKey(), event.getEventId());
		});
	}

	private static org.springframework.amqp.core.Message toMessage(OutboxEventEntity event) {
		return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
			.setContentType(MessageProperties.CONTENT_TYPE_JSON)
			.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
			.setMessageId(event.getEventId().toString())
			.build();
	}

}
