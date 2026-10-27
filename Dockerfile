# Build context is the repo root (the build reads proto/orders.proto and proto/catalog.proto):
#   docker build -f services/orders-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /repo/services/orders-service
COPY proto/ /repo/proto/
COPY services/orders-service/mvnw services/orders-service/pom.xml ./
COPY services/orders-service/.mvn/ .mvn/
RUN ./mvnw -q dependency:go-offline
COPY services/orders-service/src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /repo/services/orders-service/target/orders-service-*.jar app.jar
USER 65534
EXPOSE 50052
ENTRYPOINT ["java", "-jar", "app.jar"]
