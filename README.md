# orders-service

Java (Spring Boot) + PostgreSQL.

Carrito, pedidos y pagos simulados. Expone un servidor gRPC, publica
`order.created` y consume `stock.reserved` / `stock.failed` por RabbitMQ.
