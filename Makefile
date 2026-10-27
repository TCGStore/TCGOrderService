# Defaults for local development live in src/main/resources/application.properties.
.PHONY: run test

run:
	./mvnw spring-boot:run

# Needs Docker running: Testcontainers starts PostgreSQL and RabbitMQ for the tests.
test:
	./mvnw test
