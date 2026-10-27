package com.tcgstore.orders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.tcgstore.orders.cart.CartService;
import com.tcgstore.orders.catalog.ProductCatalog;
import com.tcgstore.orders.catalog.ProductCatalog.CatalogProduct;
import com.tcgstore.orders.events.OutboxEventEntity;
import com.tcgstore.orders.events.OutboxEventRepository;
import com.tcgstore.orders.events.RabbitConfig;
import com.tcgstore.orders.order.OrderEntity;
import com.tcgstore.orders.order.OrderEntity.CancellationReason;
import com.tcgstore.orders.order.OrderEntity.Status;
import com.tcgstore.orders.order.OrderItemEntity;
import com.tcgstore.orders.order.OrderService;
import com.tcgstore.orders.order.ShippingAddress;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class OrderFlowTest {

	// The payment limit is 20000 cents (application.properties).
	private static final Map<String, CatalogProduct> PRODUCTS = Map.of(
			"prd_pikachu", new CatalogProduct("prd_pikachu", "Pikachu", 99),
			"prd_charizard", new CatalogProduct("prd_charizard", "Charizard ex", 2499),
			"prd_box", new CatalogProduct("prd_box", "Obsidian Flames Booster Box", 14999));

	private static final ShippingAddress ADDRESS = new ShippingAddress("Ash Ketchum", "Route 1 #10", "Pallet Town", "",
			"", "CL");

	@MockitoBean
	ProductCatalog catalog;

	@Autowired
	CartService cart;

	@Autowired
	OrderService orders;

	@Autowired
	OutboxEventRepository outbox;

	@Autowired
	RabbitTemplate rabbit;

	// Every test uses its own user, so they don't see each other's carts and orders.
	private final String userId = "usr_" + UUID.randomUUID().toString().substring(0, 8);

	@BeforeEach
	void catalogReturnsKnownProducts() {
		when(catalog.findByIds(any())).thenAnswer(call -> {
			Collection<String> ids = call.getArgument(0);
			return ids.stream()
				.filter(PRODUCTS::containsKey)
				.map(PRODUCTS::get)
				.collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
		});
	}

	@Test
	void cartAddsUpQuantitiesAndRemovesWithZero() {
		cart.addItem(userId, "prd_pikachu", 2);
		cart.addItem(userId, "prd_pikachu", 1);
		var lines = cart.addItem(userId, "prd_charizard", 1);

		assertThat(lines).extracting(CartService.CartLine::productId, CartService.CartLine::quantity)
			.containsExactly(tuple("prd_pikachu", 3), tuple("prd_charizard", 1));

		assertThat(cart.updateItem(userId, "prd_pikachu", 0)).extracting(CartService.CartLine::productId)
			.containsExactly("prd_charizard");
	}

	@Test
	void addingUnknownProductIsNotFound() {
		assertStatus(() -> cart.addItem(userId, "prd_missing", 1), Code.NOT_FOUND);
	}

	@Test
	void checkoutOfEmptyCartFails() {
		assertStatus(() -> orders.checkout(userId, "ash@example.com", ADDRESS), Code.FAILED_PRECONDITION);
	}

	@Test
	void checkoutCopiesPricesEmptiesCartAndWritesOrderCreated() {
		cart.addItem(userId, "prd_pikachu", 2);
		cart.addItem(userId, "prd_charizard", 1);

		OrderEntity order = orders.checkout(userId, "ash@example.com", ADDRESS);

		assertThat(order.getStatus()).isEqualTo(Status.PENDING);
		assertThat(order.getTotalCents()).isEqualTo(2 * 99 + 2499);
		assertThat(order.getItems()).extracting(OrderItemEntity::getName).containsExactly("Pikachu", "Charizard ex");
		assertThat(cart.getCart(userId)).isEmpty();
		assertThat(eventsFor("order.created", order.getId())).hasSize(1)
			.first()
			.extracting(OutboxEventEntity::getPayload)
			.asString()
			.contains("\"userEmail\":\"ash@example.com\"", "\"totalCents\":2697", "\"productId\":\"prd_pikachu\"");
	}

	@Test
	void stockReservedOverRabbitMqConfirmsOrderWithinPaymentLimit() throws InterruptedException {
		OrderEntity order = orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_charizard", 1));

		sendStockEvent("stock.reserved", order.getId());

		assertThat(awaitStatus(order.getId(), Status.CONFIRMED)).isEqualTo(Status.CONFIRMED);
		assertThat(eventsFor("order.confirmed", order.getId())).hasSize(1);
		assertThat(eventsFor("order.created", order.getId())).first()
			.extracting(OutboxEventEntity::getPublishedAt)
			.isNotNull();
	}

	@Test
	void paymentAboveLimitIsDeclined() {
		OrderEntity order = orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_box", 2));

		orders.onStockReserved(order.getId());

		OrderEntity updated = orders.getOrder(userId, order.getId());
		assertThat(updated.getStatus()).isEqualTo(Status.CANCELLED);
		assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);
		assertThat(eventsFor("order.cancelled", order.getId())).first()
			.extracting(OutboxEventEntity::getPayload)
			.asString()
			.contains("\"reason\":\"PAYMENT_DECLINED\"");
	}

	@Test
	void stockFailedCancelsAndLaterStockEventsAreIgnored() {
		OrderEntity order = orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_pikachu", 1));

		orders.onStockFailed(order.getId());
		orders.onStockReserved(order.getId());
		orders.onStockFailed(order.getId());

		OrderEntity updated = orders.getOrder(userId, order.getId());
		assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.OUT_OF_STOCK);
		assertThat(eventsFor("order.cancelled", order.getId())).hasSize(1);
		assertThat(eventsFor("order.confirmed", order.getId())).isEmpty();
	}

	@Test
	void anotherUsersOrderIsNotFound() {
		OrderEntity order = orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_pikachu", 1));

		assertStatus(() -> orders.getOrder("usr_misty", order.getId()), Code.NOT_FOUND);
	}

	@Test
	void createOrderValidatesInput() {
		assertStatus(() -> orders.createOrder(userId, "ash@example.com", null, Map.of("prd_pikachu", 1)),
				Code.INVALID_ARGUMENT);
		assertStatus(() -> orders.createOrder(userId, "not-an-email", ADDRESS, Map.of("prd_pikachu", 1)),
				Code.INVALID_ARGUMENT);
		assertStatus(() -> orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_pikachu", 0)),
				Code.INVALID_ARGUMENT);
		assertStatus(() -> orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_missing", 1)),
				Code.FAILED_PRECONDITION);
	}

	@Test
	void listOrdersIsNewestFirstAndPaged() {
		orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_pikachu", 1));
		OrderEntity newest = orders.createOrder(userId, "ash@example.com", ADDRESS, Map.of("prd_charizard", 1));

		var page = orders.listOrders(userId, 1, 1);

		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting(OrderEntity::getId).containsExactly(newest.getId());
	}

	private void sendStockEvent(String type, String orderId) {
		String json = """
				{"eventId":"%s","type":"%s","occurredAt":"%s","data":{"orderId":"%s"}}
				""".formatted(UUID.randomUUID(), type, Instant.now(), orderId);
		rabbit.send(RabbitConfig.EXCHANGE, type,
				MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).build());
	}

	private Status awaitStatus(String orderId, Status expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		Status status = orders.getOrder(userId, orderId).getStatus();
		while (status != expected && Instant.now().isBefore(deadline)) {
			Thread.sleep(100);
			status = orders.getOrder(userId, orderId).getStatus();
		}
		return status;
	}

	private List<OutboxEventEntity> eventsFor(String routingKey, String orderId) {
		return outbox.findByRoutingKeyOrderByIdAsc(routingKey)
			.stream()
			.filter(event -> event.getPayload().contains("\"orderId\":\"" + orderId + "\""))
			.toList();
	}

	private static void assertStatus(Runnable call, Code code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,
				e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
	}

}
