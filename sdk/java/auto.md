# Java auto-instrumentation — zero-code setup via javaagent

Java's auto-instrumentation requires zero code changes. You attach a JAR at JVM startup and the agent instruments all supported libraries via bytecode manipulation at class-load time.

---

## Step 1 — Download the agent

```bash
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar
```

Add to `.gitignore`:

```
opentelemetry-javaagent.jar
```

Download it in your Dockerfile instead of committing it:

```dockerfile
RUN curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
    -o /otel-agent.jar
```

## Step 2 — Set env vars

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
```

## Step 3 — Run with the agent

```bash
java \
  -javaagent:./opentelemetry-javaagent.jar \
  -jar target/my-app.jar
```

The `-javaagent` flag must come before `-jar`. The agent runs before `main()`.

## Spring Boot with Maven wrapper

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-javaagent:./opentelemetry-javaagent.jar"
```

## Gradle

In `build.gradle`:

```groovy
bootRun {
    jvmArgs = ["-javaagent:${project.rootDir}/opentelemetry-javaagent.jar"]
}
```

---

## What gets instrumented automatically

Everything in [opentelemetry.io/docs/zero-code/java/agent/supported-libraries/](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/). The highlights:

- Spring MVC, Spring WebFlux, Spring Boot Actuator
- gRPC (server and client)
- JDBC (all drivers: PostgreSQL, MySQL, H2, Oracle, SQLite)
- Hibernate / JPA
- Apache HttpClient, OkHttp, WebClient
- Kafka producer and consumer
- Redis (Jedis, Lettuce)
- MongoDB driver
- RabbitMQ, JMS

---

## Verify it's working

Make one HTTP request to your Spring Boot endpoint, then:

```bash
docker logs otel-collector --tail 30
```

Look for `ScopeSpans` with `http.request.method` and `http.route` attributes.

---

## Disabling specific instrumentations

If an instrumentation is noisy or causes issues, disable it via env var:

```bash
# Disable DNS spans (very noisy in some environments)
export OTEL_INSTRUMENTATION_DNS_ENABLED=false

# Disable all database statement capture (for PII/security reasons)
export OTEL_INSTRUMENTATION_JDBC_STATEMENT_SANITIZER_ENABLED=true

# Disable a specific library entirely
export OTEL_INSTRUMENTATION_SPRING_WEBMVC_ENABLED=false
```
