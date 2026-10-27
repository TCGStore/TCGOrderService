package com.tcgstore.orders.cart;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.tcgstore.orders.catalog.ProductCatalog;
import com.tcgstore.orders.catalog.ProductCatalog.CatalogProduct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.tcgstore.orders.grpc.GrpcErrors.invalidArgument;
import static com.tcgstore.orders.grpc.GrpcErrors.notFound;

/** The cart only stores product ids and quantities; names and prices always come fresh from the catalog. */
@Service
public class CartService {

	public static final int MAX_QUANTITY_PER_PRODUCT = 99;

	public record CartLine(String productId, String name, long unitPriceCents, int quantity) {

		public long subtotalCents() {
			return unitPriceCents * quantity;
		}

	}

	private final CartItemRepository items;

	private final ProductCatalog catalog;

	private final Clock clock;

	public CartService(CartItemRepository items, ProductCatalog catalog, Clock clock) {
		this.items = items;
		this.catalog = catalog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CartLine> getCart(String userId) {
		return withCatalogData(items.findByUserIdOrderByAddedAtAsc(userId));
	}

	@Transactional
	public List<CartLine> addItem(String userId, String productId, int quantity) {
		if (quantity <= 0) {
			throw invalidArgument("quantity must be positive");
		}
		if (catalog.findByIds(List.of(productId)).isEmpty()) {
			throw notFound("product \"" + productId + "\" not found");
		}

		var existing = items.findByUserIdAndProductId(userId, productId);
		int total = quantity + existing.map(CartItemEntity::getQuantity).orElse(0);
		checkMaxQuantity(total);
		existing.ifPresentOrElse(item -> item.setQuantity(total),
				() -> items.save(new CartItemEntity(userId, productId, total, clock.instant())));
		return getCart(userId);
	}

	@Transactional
	public List<CartLine> updateItem(String userId, String productId, int quantity) {
		if (quantity < 0) {
			throw invalidArgument("quantity can't be negative");
		}
		checkMaxQuantity(quantity);

		var item = items.findByUserIdAndProductId(userId, productId)
			.orElseThrow(() -> notFound("product \"" + productId + "\" is not in the cart"));
		if (quantity == 0) {
			items.delete(item);
		}
		else {
			item.setQuantity(quantity);
		}
		return getCart(userId);
	}

	@Transactional
	public void clear(String userId) {
		items.deleteByUserId(userId);
	}

	/** Raw cart contents for checkout, which re-reads prices itself. */
	@Transactional(readOnly = true)
	public List<CartItemEntity> rawItems(String userId) {
		return items.findByUserIdOrderByAddedAtAsc(userId);
	}

	// Products removed from the catalog are hidden instead of failing the whole cart.
	private List<CartLine> withCatalogData(List<CartItemEntity> cartItems) {
		Map<String, CatalogProduct> products = catalog
			.findByIds(cartItems.stream().map(CartItemEntity::getProductId).toList());
		return cartItems.stream()
			.filter(item -> products.containsKey(item.getProductId()))
			.map(item -> {
				CatalogProduct product = products.get(item.getProductId());
				return new CartLine(product.id(), product.name(), product.priceCents(), item.getQuantity());
			})
			.toList();
	}

	private static void checkMaxQuantity(int quantity) {
		if (quantity > MAX_QUANTITY_PER_PRODUCT) {
			throw invalidArgument("at most " + MAX_QUANTITY_PER_PRODUCT + " units per product");
		}
	}

}
