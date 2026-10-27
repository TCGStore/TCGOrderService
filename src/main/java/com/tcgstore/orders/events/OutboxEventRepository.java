package com.tcgstore.orders.events;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

	// SKIP LOCKED lets several replicas run the relay without sending the same event twice.
	@Query(value = """
			select * from outbox_events
			where published_at is null
			order by id
			limit :limit
			for update skip locked
			""", nativeQuery = true)
	List<OutboxEventEntity> lockPending(int limit);

	List<OutboxEventEntity> findByRoutingKeyOrderByIdAsc(String routingKey);

}
