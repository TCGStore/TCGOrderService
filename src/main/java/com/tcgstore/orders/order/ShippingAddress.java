package com.tcgstore.orders.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record ShippingAddress(
		@Column(name = "shipping_recipient_name") String recipientName,
		@Column(name = "shipping_street") String street,
		@Column(name = "shipping_city") String city,
		@Column(name = "shipping_region") String region,
		@Column(name = "shipping_postal_code") String postalCode,
		@Column(name = "shipping_country") String country) {
}
