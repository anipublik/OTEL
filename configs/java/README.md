# Java / Spring Boot — OpenTelemetry setup

Java is the easiest language to instrument because OpenTelemetry provides a zero-code Java agent. You don't write any SDK code. You attach a jar at startup and set two env vars.

---

## Prerequisites

- Java 11+
- Maven or Gradle project
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Download the Java agent

```bash
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar
```

Add the jar to your project root or a `lib/` directory. Do not commit it to version control — add it to `.gitignore` and download it in your Dockerfile instead.

## Run your app with the agent

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0

java \
  -javaagent:./opentelemetry-javaagent.jar \
  -jar target/my-app.jar
```

The `-javaagent` flag must come before `-jar`. The agent injects bytecode instrumentation at class-loading time, before your `main` method runs.

## Docker usage

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/my-app.jar app.jar

# Download the agent at image build time
RUN curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
    -o /otel-agent.jar

ENTRYPOINT ["java", "-javaagent:/otel-agent.jar", "-jar", "app.jar"]
```

Set `OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_ENDPOINT` as Docker environment variables.

## Spring Boot — application.properties alternative

If you prefer config-file style over env vars:

```properties
# application.properties
otel.service.name=my-service
otel.exporter.otlp.endpoint=http://localhost:4317
otel.resource.attributes=deployment.environment=local
```

## Verify it's working

Start your app and make one HTTP request. Then:

```bash
docker logs otel-collector --tail 50
```

You should see spans with `http.request.method`, `http.route`, and your service name. Spring Boot MVC and WebFlux are both covered automatically.

---

## What gets instrumented automatically

The Java agent covers (among many others):

- Spring MVC, Spring WebFlux, Spring Boot Actuator
- JDBC (all drivers: PostgreSQL, MySQL, H2, Oracle)
- Hibernate / JPA
- gRPC (server and client)
- Apache HttpClient, OkHttp, WebClient
- Kafka producer and consumer
- Redis (Jedis, Lettuce)
- MongoDB driver
- Scheduling frameworks (Quartz, Spring Scheduler)

The full list: [opentelemetry.io/docs/zero-code/java/agent/supported-libraries/](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/)

---

## Adding custom spans (see also `sdk-bootstrap.java`)

When the agent is attached, you can get a tracer from the global provider — no dependency injection needed:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

Tracer tracer = GlobalOpenTelemetry.getTracer("com.example.my-component");
```

See `sdk-bootstrap.java` for the complete manual SDK setup if you need more control.
