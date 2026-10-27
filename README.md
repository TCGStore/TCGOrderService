# orders-service

Java 21 + Spring Boot 4.1 + PostgreSQL. Carrito, pedidos y pagos simulados. Implementa
`proto/orders.proto` en el puerto gRPC 50052, llama a catalog-service por gRPC para
leer nombres y precios, y coordina la saga de compra por RabbitMQ (`proto/events.md`).

- **Publica** `order.created`, `order.confirmed` y `order.cancelled` en el exchange `tcg.events`.
- **Consume** `stock.reserved` y `stock.failed` (cola `orders-service.events`).

## Estructura

```
src/main/java/com/tcgstore/orders/
  grpc/       los 8 RPCs del .proto, conversión entidad ↔ proto y manejo de errores gRPC
  cart/       carrito (solo guarda producto + cantidad; los precios se leen del catálogo)
  order/      pedidos, sus estados y el pago simulado
  catalog/    cliente gRPC de catalog-service
  events/     outbox, envío a RabbitMQ y consumo de stock.*
src/main/resources/
  application.properties     configuración (con defaults para desarrollo local)
  db/migration/V1__init.sql  tablas (Flyway las crea al arrancar)
src/test/                    tests con PostgreSQL y RabbitMQ reales (Testcontainers)
```

El código gRPC se genera desde `proto/orders.proto` y `proto/catalog.proto` en cada build
(plugin `protobuf-maven-plugin`) y no se sube al repo. Por eso el Dockerfile se construye
desde la raíz del repo.

## La saga desde el lado de orders

```
Checkout / CreateOrder ─► pedido PENDING ─► order.created
stock.failed           ─► CANCELLED (OUT_OF_STOCK)    ─► order.cancelled
stock.reserved         ─► pago simulado
                           ├─ aprobado  ─► CONFIRMED                    ─► order.confirmed
                           └─ rechazado ─► CANCELLED (PAYMENT_DECLINED) ─► order.cancelled
```

- **Pago simulado:** una tarjeta ficticia con cupo de `PAYMENT_LIMIT_CENTS` ($200 por
  defecto). Hasta ese total se aprueba; sobre eso se rechaza. Es determinista a propósito,
  para poder mostrar los dos casos en la demo (por ejemplo, 2 booster boxes = $299.98 → rechazado).
- **Eventos repetidos:** solo un pedido `PENDING` reacciona a `stock.*`. Si RabbitMQ
  entrega dos veces el mismo evento, la segunda vez el pedido ya no está pendiente y se ignora.
- **Precios congelados:** al crear el pedido se copian nombre y precio de cada producto;
  si el catálogo cambia después, los pedidos antiguos no cambian.

### ¿Qué es el outbox?

Guardar el pedido en PostgreSQL y publicar el evento en RabbitMQ son dos sistemas
distintos. Si el servicio se cae justo entre ambas cosas, quedaría un pedido sin evento
(y la saga se queda pegada) o un evento de un pedido que no existe.

La solución es el **patrón outbox**: el evento se guarda en la tabla `outbox_events`
**en la misma transacción** que el pedido (o se guardan los dos, o ninguno). Después,
`OutboxRelay` revisa la tabla cada 500 ms, envía a RabbitMQ lo pendiente, espera la
confirmación de RabbitMQ y lo marca como publicado. Si se cae a mitad de camino, el
evento se reenvía con el mismo `eventId`, y los consumidores ya descartan los repetidos.

## Correr en local

Requisitos: Java 21 o superior y Docker. **No hace falta instalar Maven**: `./mvnw` lo descarga.

```bash
# desde la raíz del repo: dependencias + catalog-service (orders le pide los precios)
docker compose up -d postgres rabbitmq catalog-service
docker compose run --rm --entrypoint /app/seed catalog-service

# desde services/orders-service
make run     # servidor en :50052
make test    # necesita Docker corriendo (Testcontainers)
```

O todo con Docker, desde la raíz:

```bash
docker compose up -d --build orders-service inventory-service
```

Para que los pedidos se confirmen, inventory-service también tiene que estar corriendo y con
stock cargado: si no, los pedidos se quedan en `PENDING` esperando `stock.reserved`.

## Probar con grpcurl

```bash
grpcurl -plaintext localhost:50052 list

grpcurl -plaintext -d '{"user_id":"usr_ash","product_id":"prd_mew_006_en_nm","quantity":1}' \
  localhost:50052 tcgstore.orders.OrdersService/AddCartItem

grpcurl -plaintext -d '{"user_id":"usr_ash","user_email":"ash@example.com",
  "shipping_address":{"recipient_name":"Ash Ketchum","street":"Route 1 #10","city":"Pallet Town","country":"CL"}}' \
  localhost:50052 tcgstore.orders.OrdersService/Checkout

# un par de segundos después ya debería estar CONFIRMED
grpcurl -plaintext -d '{"user_id":"usr_ash","order_id":"ord_..."}' \
  localhost:50052 tcgstore.orders.OrdersService/GetOrder
```

## Variables de entorno

| Variable | Default (local) | |
|---|---|---|
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/orders` | base `orders` (la crea el contenedor de postgres) |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `tcg` / `tcg` | |
| `RABBITMQ_URL` | `amqp://tcg:tcg@localhost:5672` | |
| `CATALOG_GRPC_TARGET` | `localhost:50051` | `catalog-service:50051` dentro de Docker/Kubernetes |
| `GRPC_PORT` | `50052` | |
| `PAYMENT_LIMIT_CENTS` | `20000` | cupo de la tarjeta simulada |

## Notas para quien consume este servicio

- `user_id` y `user_email` los pone el BFF desde el JWT; nunca deben venir del cliente.
- `GetOrder` de un pedido de otro usuario devuelve `NOT_FOUND` (no revela que existe).
- `Checkout` y `CreateOrder` responden de inmediato con el pedido en `PENDING`; el estado
  final llega unos instantes después, por la saga. El frontend puede volver a consultar
  `GetOrder`, o mostrar la notificación que genera notifications-service.
- Errores: `INVALID_ARGUMENT` (datos inválidos), `NOT_FOUND`, `FAILED_PRECONDITION`
  (carrito vacío, producto que ya no está en el catálogo), `UNAVAILABLE` (catalog-service caído).
- Máximo 99 unidades por producto. Un producto que se borra del catálogo desaparece del carrito.
- Los montos `int64` (`*_cents`) aparecen como string en el JSON de grpcurl; con
  `@grpc/proto-loader` conviene usar `longs: Number`.
- Si inventory-service nunca responde, el pedido queda en `PENDING` (no hay timeout del lado de orders).
