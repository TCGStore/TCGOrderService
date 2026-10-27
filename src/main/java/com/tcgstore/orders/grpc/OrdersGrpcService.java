package com.tcgstore.orders.grpc;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

import com.tcgstore.orders.cart.CartService;
import com.tcgstore.orders.order.OrderEntity;
import com.tcgstore.orders.order.OrderService;
import io.grpc.stub.StreamObserver;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import static com.tcgstore.orders.grpc.GrpcErrors.invalidArgument;

/** Spring gRPC registers this bean on spring.grpc.server.port because it implements a generated service. */
@Service
public class OrdersGrpcService extends OrdersServiceGrpc.OrdersServiceImplBase {

	private static final int DEFAULT_PAGE_SIZE = 20;

	private static final int MAX_PAGE_SIZE = 100;

	private final CartService cart;

	private final OrderService orders;

	public OrdersGrpcService(CartService cart, OrderService orders) {
		this.cart = cart;
		this.orders = orders;
	}

	@Override
	public void getCart(GetCartRequest request, StreamObserver<Cart> response) {
		String userId = userId(request.getUserId());
		reply(response, () -> ProtoMapper.cart(userId, cart.getCart(userId)));
	}

	@Override
	public void addCartItem(AddCartItemRequest request, StreamObserver<Cart> response) {
		String userId = userId(request.getUserId());
		String productId = productId(request.getProductId());
		reply(response, () -> ProtoMapper.cart(userId, cart.addItem(userId, productId, request.getQuantity())));
	}

	@Override
	public void updateCartItem(UpdateCartItemRequest request, StreamObserver<Cart> response) {
		String userId = userId(request.getUserId());
		String productId = productId(request.getProductId());
		reply(response, () -> ProtoMapper.cart(userId, cart.updateItem(userId, productId, request.getQuantity())));
	}

	@Override
	public void clearCart(ClearCartRequest request, StreamObserver<Cart> response) {
		String userId = userId(request.getUserId());
		cart.clear(userId);
		reply(response, () -> Cart.newBuilder().setUserId(userId).build());
	}

	@Override
	public void checkout(CheckoutRequest request, StreamObserver<Order> response) {
		String userId = userId(request.getUserId());
		var address = ProtoMapper.shippingAddress(request.hasShippingAddress(), request.getShippingAddress());
		reply(response, () -> ProtoMapper.order(orders.checkout(userId, request.getUserEmail().trim(), address)));
	}

	@Override
	public void createOrder(CreateOrderRequest request, StreamObserver<Order> response) {
		String userId = userId(request.getUserId());
		var address = ProtoMapper.shippingAddress(request.hasShippingAddress(), request.getShippingAddress());
		var quantities = new LinkedHashMap<String, Integer>();
		for (OrderItemInput item : request.getItemsList()) {
			quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}
		reply(response, () -> ProtoMapper
			.order(orders.createOrder(userId, request.getUserEmail().trim(), address, quantities)));
	}

	@Override
	public void getOrder(GetOrderRequest request, StreamObserver<Order> response) {
		String userId = userId(request.getUserId());
		if (request.getOrderId().isBlank()) {
			throw invalidArgument("order_id is required");
		}
		reply(response, () -> ProtoMapper.order(orders.getOrder(userId, request.getOrderId())));
	}

	@Override
	public void listOrders(ListOrdersRequest request, StreamObserver<ListOrdersResponse> response) {
		String userId = userId(request.getUserId());
		if (request.getPage() < 0 || request.getPageSize() < 0) {
			throw invalidArgument("page and page_size can't be negative");
		}
		int page = request.getPage() == 0 ? 1 : request.getPage();
		int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), MAX_PAGE_SIZE);

		reply(response, () -> {
			Page<OrderEntity> result = orders.listOrders(userId, page, pageSize);
			return ListOrdersResponse.newBuilder()
				.addAllOrders(result.map(ProtoMapper::order).getContent())
				.setTotalCount((int) result.getTotalElements())
				.build();
		});
	}

	// Exceptions thrown here or in the services are turned into gRPC statuses by GrpcErrors.
	private static <T> void reply(StreamObserver<T> response, Supplier<T> call) {
		response.onNext(call.get());
		response.onCompleted();
	}

	private static String userId(String userId) {
		if (userId.isBlank()) {
			throw invalidArgument("user_id is required");
		}
		return userId;
	}

	private static String productId(String productId) {
		if (productId.isBlank()) {
			throw invalidArgument("product_id is required");
		}
		return productId;
	}

}
