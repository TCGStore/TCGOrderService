package com.tcgstore.orders.cart;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> {

	List<CartItemEntity> findByUserIdOrderByAddedAtAsc(String userId);

	Optional<CartItemEntity> findByUserIdAndProductId(String userId, String productId);

	@Modifying
	@Query("delete from CartItemEntity c where c.userId = :userId")
	void deleteByUserId(String userId);

}
