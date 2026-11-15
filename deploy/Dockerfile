# QueueGuard shared multi-stage build.
# Usage (from repo root):
#   docker build -f deploy/Dockerfile --build-arg SERVICE_NAME=api-gateway -t queueguard/api-gateway:1.0.0 .
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SERVICE_NAME
WORKDIR /workspace
COPY pom.xml ./
COPY common ./common
COPY services ./services
RUN mvn -q -pl services/${SERVICE_NAME} -am -DskipTests package

FROM eclipse-temurin:17-jre-jammy
ARG SERVICE_NAME
RUN useradd --system --uid 1001 --no-create-home queueguard
WORKDIR /app
COPY --from=build /workspace/services/${SERVICE_NAME}/target/*.jar /app/app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
