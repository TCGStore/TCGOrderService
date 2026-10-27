package com.tcgstore.orders.events;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topology from proto/events.md; Spring declares it in RabbitMQ on startup if it doesn't exist. */
@Configuration
public class RabbitConfig {

	public static final String EXCHANGE = "tcg.events";

	public static final String QUEUE = "orders-service.events";

	@Bean
	Declarables ordersTopology() {
		TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
		Queue queue = QueueBuilder.durable(QUEUE).build();
		return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with("stock.reserved"),
				BindingBuilder.bind(queue).to(exchange).with("stock.failed"));
	}

}
