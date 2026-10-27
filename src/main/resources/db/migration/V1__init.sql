create table cart_items (
    id          bigserial primary key,
    user_id     varchar(64)  not null,
    product_id  varchar(64)  not null,
    quantity    int          not null check (quantity > 0),
    added_at    timestamptz  not null,
    unique (user_id, product_id)
);

create table orders (
    id                        varchar(32)  primary key,
    user_id                   varchar(64)  not null,
    user_email                varchar(254) not null,
    status                    varchar(20)  not null,
    cancellation_reason       varchar(30),
    total_cents               bigint       not null,
    shipping_recipient_name   varchar(200) not null,
    shipping_street           varchar(200) not null,
    shipping_city             varchar(200) not null,
    shipping_region           varchar(200) not null,
    shipping_postal_code      varchar(200) not null,
    shipping_country          varchar(200) not null,
    created_at                timestamptz  not null,
    updated_at                timestamptz  not null,
    version                   bigint       not null
);

create index orders_user_created_idx on orders (user_id, created_at desc);

-- Name and price are copied from the catalog when the order is created.
create table order_items (
    id                bigserial    primary key,
    order_id          varchar(32)  not null references orders (id) on delete cascade,
    product_id        varchar(64)  not null,
    name              varchar(200) not null,
    unit_price_cents  bigint       not null,
    quantity          int          not null check (quantity > 0)
);

create index order_items_order_idx on order_items (order_id);

-- Transactional outbox: events are written in the same transaction as the order
-- and OutboxRelay sends them to RabbitMQ afterwards.
create table outbox_events (
    id            bigserial    primary key,
    event_id      uuid         not null unique,
    routing_key   varchar(50)  not null,
    payload       text         not null,
    created_at    timestamptz  not null,
    published_at  timestamptz
);

create index outbox_events_pending_idx on outbox_events (id) where published_at is null;
