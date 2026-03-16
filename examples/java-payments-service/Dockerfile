# Multi-stage build: compile with JDK, run with JRE + javaagent.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && mvn -q package -DskipTests \
    && apt-get purge -y maven && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

FROM eclipse-temurin:17-jre
WORKDIR /app

# Download the javaagent at image build time — not committed to git.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && curl -fsSL \
      https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
      -o /otel-agent.jar \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/payments-api-*.jar app.jar

# OTEL env vars — override at runtime via docker-compose or K8s manifests.
ENV OTEL_SERVICE_NAME=payments-api
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4317
ENV OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
ENV OTEL_METRICS_EXPORTER=otlp
ENV OTEL_LOGS_EXPORTER=otlp
# Export metrics every 10s so Grafana panels update quickly during the demo.
ENV OTEL_METRIC_EXPORT_INTERVAL=10000

EXPOSE 8080

ENTRYPOINT ["java", "-javaagent:/otel-agent.jar", "-jar", "app.jar"]
