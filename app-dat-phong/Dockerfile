FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# healthcheck trong docker-compose dùng curl: cài tường minh, không phụ thuộc base image có sẵn hay không
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd -r -u 1001 spring && chown spring /app
USER spring
COPY --from=build /build/target/*.jar app.jar
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
