# syntax=docker/dockerfile:1
# One Dockerfile for all four services: docker build --build-arg SERVICE=order-service -t order-platform/order-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
ARG SERVICE
# tests run in CI, not in the image build; -am also builds the shared libraries the service depends on
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/${SERVICE} -am package -DskipTests -Djacoco.skip=true \
 && cp "$(ls services/${SERVICE}/target/*.jar | grep -v -e original -e plain | head -n 1)" /app.jar \
 && (jar tf /app.jar | grep -q BOOT-INF/classes || (echo "ERROR: not an executable Spring Boot jar" && exit 1))

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
# size the heap from the container memory limit instead of the host's RAM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
