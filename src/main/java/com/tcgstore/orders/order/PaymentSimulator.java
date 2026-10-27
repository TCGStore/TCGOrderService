package com.tcgstore.orders.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A fake card with a fixed limit: orders above it are declined. Deterministic on purpose,
 * so the demo can show both outcomes on demand.
 */
@Component
public class PaymentSimulator {

	private final long limitCents;

	public PaymentSimulator(@Value("${orders.payment.limit-cents}") long limitCents) {
		this.limitCents = limitCents;
	}

	public boolean charge(OrderEntity order) {
		return order.getTotalCents() <= limitCents;
	}

}
