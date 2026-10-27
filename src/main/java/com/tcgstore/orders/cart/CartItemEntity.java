package com.tcgstore.orders.cart;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cart_items")
public class CartItemEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String userId;

	private String productId;

	private int quantity;

	private Instant addedAt;

	protected CartItemEntity() {
	}

	public CartItemEntity(String userId, String productId, int quantity, Instant addedAt) {
		this.userId = userId;
		this.productId = productId;
		this.quantity = quantity;
		this.addedAt = addedAt;
	}

	public String getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

}
