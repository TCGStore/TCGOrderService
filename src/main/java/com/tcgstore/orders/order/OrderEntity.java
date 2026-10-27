package com.tcgstore.orders.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class OrderEntity {

	public enum Status {

		PENDING, CONFIRMED, CANCELLED

	}

	/** Same values as the "reason" field of order.cancelled in proto/events.md. */
	public enum CancellationReason {

		OUT_OF_STOCK, PAYMENT_DECLINED

	}

	public record ItemSnapshot(String productId, String name, long unitPriceCents, int quantity) {
	}

	@Id
	private String id;

	private String userId;

	private String userEmail;

	@Enumerated(EnumType.STRING)
	private Status status;

	@Enumerated(EnumType.STRING)
	private CancellationReason cancellationReason;

	private long totalCents;

	@Embedded
	private ShippingAddress shippingAddress;

	// Eager because orders are mapped to protobuf after the transaction ends;
	// BatchSize loads the items of a whole ListOrders page in one query.
	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@BatchSize(size = 100)
	@OrderBy("id")
	private List<OrderItemEntity> items = new ArrayList<>();

	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	protected OrderEntity() {
	}

	public static OrderEntity pending(String userId, String userEmail, ShippingAddress address,
			List<ItemSnapshot> items, Instant now) {
		var order = new OrderEntity();
		order.id = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		order.userId = userId;
		order.userEmail = userEmail;
		order.status = Status.PENDING;
		order.shippingAddress = address;
		order.createdAt = now;
		order.updatedAt = now;
		for (ItemSnapshot item : items) {
			order.items.add(new OrderItemEntity(order, item.productId(), item.name(), item.unitPriceCents(),
					item.quantity()));
		}
		order.totalCents = order.items.stream().mapToLong(OrderItemEntity::getSubtotalCents).sum();
		return order;
	}

	public void confirm(Instant now) {
		this.status = Status.CONFIRMED;
		this.updatedAt = now;
	}

	public void cancel(CancellationReason reason, Instant now) {
		this.status = Status.CANCELLED;
		this.cancellationReason = reason;
		this.updatedAt = now;
	}

	public String getId() {
		return id;
	}

	public String getUserId() {
		return userId;
	}

	public String getUserEmail() {
		return userEmail;
	}

	public Status getStatus() {
		return status;
	}

	public CancellationReason getCancellationReason() {
		return cancellationReason;
	}

	public long getTotalCents() {
		return totalCents;
	}

	public ShippingAddress getShippingAddress() {
		return shippingAddress;
	}

	public List<OrderItemEntity> getItems() {
		return items;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
