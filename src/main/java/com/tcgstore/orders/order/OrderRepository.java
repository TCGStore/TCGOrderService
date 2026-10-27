package com.tcgstore.orders.order;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

	Optional<OrderEntity> findByIdAndUserId(String id, String userId);

	Page<OrderEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

}
