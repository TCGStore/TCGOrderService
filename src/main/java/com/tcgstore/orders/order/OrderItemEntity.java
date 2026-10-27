package com.tcgstore.orders.order;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItemEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id")
	private OrderEntity order;

	private String productId;

	private String name;

	private long unitPriceCents;

	private int quantity;

	protected OrderItemEntity() {
	}

	OrderItemEntity(OrderEntity order, String productId, String name, long unitPriceCents, int quantity) {
		this.order = order;
		this.productId = productId;
		this.name = name;
		this.unitPriceCents = unitPriceCents;
		this.quantity = quantity;
	}

	public String getProductId() {
		return productId;
	}

	public String getName() {
		return name;
	}

	public long getUnitPriceCents() {
		return unitPriceCents;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getSubtotalCents() {
		return unitPriceCents * quantity;
	}

}
