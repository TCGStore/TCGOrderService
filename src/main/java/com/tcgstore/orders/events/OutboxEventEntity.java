package com.tcgstore.orders.events;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private UUID eventId;

	private String routingKey;

	/** The full JSON envelope, exactly as it goes to RabbitMQ. */
	private String payload;

	private Instant createdAt;

	private Instant publishedAt;

	protected OutboxEventEntity() {
	}

	OutboxEventEntity(UUID eventId, String routingKey, String payload, Instant createdAt) {
		this.eventId = eventId;
		this.routingKey = routingKey;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	void markPublished(Instant now) {
		this.publishedAt = now;
	}

	public UUID getEventId() {
		return eventId;
	}

	public String getRoutingKey() {
		return routingKey;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

}
