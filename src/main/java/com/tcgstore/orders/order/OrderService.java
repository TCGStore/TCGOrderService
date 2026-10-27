package com.tcgstore.orders.order;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.tcgstore.orders.cart.CartItemEntity;
import com.tcgstore.orders.cart.CartService;
import com.tcgstore.orders.catalog.ProductCatalog;
import com.tcgstore.orders.catalog.ProductCatalog.CatalogProduct;
import com.tcgstore.orders.events.OrderEvents;
import com.tcgstore.orders.order.OrderEntity.CancellationReason;
import com.tcgstore.orders.order.OrderEntity.ItemSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.tcgstore.orders.grpc.GrpcErrors.failedPrecondition;
import static com.tcgstore.orders.grpc.GrpcErrors.invalidArgument;
import static com.tcgstore.orders.grpc.GrpcErrors.notFound;

@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

	private final OrderRepository orders;

	private final CartService cart;

	private final ProductCatalog catalog;

	private final PaymentSimulator payments;

	private final OrderEvents events;

	private final Clock clock;

	public OrderService(OrderRepository orders, CartService cart, ProductCatalog catalog, PaymentSimulator payments,
			OrderEvents events, Clock clock) {
		this.orders = orders;
		this.cart = cart;
		this.catalog = catalog;
		this.payments = payments;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public OrderEntity checkout(String userId, String userEmail, ShippingAddress address) {
		var quantities = new LinkedHashMap<String, Integer>();
		for (CartItemEntity item : cart.rawItems(userId)) {
			quantities.put(item.getProductId(), item.getQuantity());
		}
		if (quantities.isEmpty()) {
			throw failedPrecondition("cart is empty");
		}
		OrderEntity order = place(userId, userEmail, address, quantities);
		cart.clear(userId);
		return order;
	}

	/** Places an order without touching the cart; repeated products are added up. */
	@Transactional
	public OrderEntity createOrder(String userId, String userEmail, ShippingAddress address,
			Map<String, Integer> quantities) {
		if (quantities.isEmpty()) {
			throw invalidArgument("items can't be empty");
		}
		quantities.forEach((productId, quantity) -> {
			if (productId.isBlank()) {
				throw invalidArgument("items[].product_id is required");
			}
			if (quantity <= 0 || quantity > CartService.MAX_QUANTITY_PER_PRODUCT) {
				throw invalidArgument("quantity of " + productId + " must be between 1 and "
						+ CartService.MAX_QUANTITY_PER_PRODUCT);
			}
		});
		return place(userId, userEmail, address, quantities);
	}

	@Transactional(readOnly = true)
	public OrderEntity getOrder(String userId, String orderId) {
		// Someone else's order looks exactly like a missing one.
		return orders.findByIdAndUserId(orderId, userId)
			.orElseThrow(() -> notFound("order \"" + orderId + "\" not found"));
	}

	@Transactional(readOnly = true)
	public Page<OrderEntity> listOrders(String userId, int page, int pageSize) {
		return orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page - 1, pageSize));
	}

	/** Inventory holds the stock, so the payment can be attempted. */
	@Transactional
	public void onStockReserved(String orderId) {
		OrderEntity order = pendingOrder(orderId, "stock.reserved");
		if (order == null) {
			return;
		}
		if (payments.charge(order)) {
			order.confirm(clock.instant());
			events.orderConfirmed(order);
			log.info("Order {} confirmed", orderId);
		}
		else {
			order.cancel(CancellationReason.PAYMENT_DECLINED, clock.instant());
			events.orderCancelled(order);
			log.info("Order {} cancelled: payment declined", orderId);
		}
	}

	@Transactional
	public void onStockFailed(String orderId) {
		OrderEntity order = pendingOrder(orderId, "stock.failed");
		if (order == null) {
			return;
		}
		order.cancel(CancellationReason.OUT_OF_STOCK, clock.instant());
		events.orderCancelled(order);
		log.info("Order {} cancelled: out of stock", orderId);
	}

	// Only PENDING orders react to stock events, which makes redelivered events harmless.
	private OrderEntity pendingOrder(String orderId, String eventType) {
		OrderEntity order = orders.findById(orderId).orElse(null);
		if (order == null) {
			log.warn("{} for unknown order {}", eventType, orderId);
			return null;
		}
		if (order.getStatus() != OrderEntity.Status.PENDING) {
			log.info("{} for order {} ignored: already {}", eventType, orderId, order.getStatus());
			return null;
		}
		return order;
	}

	private OrderEntity place(String userId, String userEmail, ShippingAddress address,
			Map<String, Integer> quantities) {
		if (!EMAIL.matcher(userEmail).matches()) {
			throw invalidArgument("user_email is not valid");
		}
		validate(address);

		Map<String, CatalogProduct> products = catalog.findByIds(quantities.keySet());
		List<String> missing = quantities.keySet().stream().filter(id -> !products.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			throw failedPrecondition("products no longer in the catalog: " + String.join(", ", missing));
		}

		List<ItemSnapshot> items = quantities.entrySet().stream().map(entry -> {
			CatalogProduct product = products.get(entry.getKey());
			return new ItemSnapshot(product.id(), product.name(), product.priceCents(), entry.getValue());
		}).toList();

		OrderEntity order = orders.save(OrderEntity.pending(userId, userEmail, address, items, clock.instant()));
		events.orderCreated(order);
		log.info("Order {} created for user {} ({} cents)", order.getId(), userId, order.getTotalCents());
		return order;
	}

	private static void validate(ShippingAddress address) {
		if (address == null) {
			throw invalidArgument("shipping_address is required");
		}
		require(address.recipientName(), "recipient_name");
		require(address.street(), "street");
		require(address.city(), "city");
		require(address.country(), "country");
	}

	private static void require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw invalidArgument("shipping_address." + field + " is required");
		}
	}

}
