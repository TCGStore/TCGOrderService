FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY proto/ proto/
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN ./mvnw -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/orders-service-*.jar app.jar
USER 65534
EXPOSE 50052
ENTRYPOINT ["java", "-jar", "app.jar"]
