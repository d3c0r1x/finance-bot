# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17.0.20_8-jdk-noble AS build
WORKDIR /workspace

# The checked-in wrapper pins Gradle 9.8.0 and verifies its distribution checksum.
COPY gradlew settings.gradle.kts ./
COPY gradle ./gradle
COPY services/core/build.gradle.kts services/core/gradle.lockfile ./services/core/
COPY services/core/src ./services/core/src

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :services:core:bootJar \
    && jar_file="$(find services/core/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$jar_file" \
    && cp "$jar_file" /workspace/finance-core.jar

# Use a tiny JDK-compiled probe so the runtime image needs no package manager,
# curl, or wget merely to report the Spring health endpoint.
RUN <<'EOF'
cat > /tmp/Healthcheck.java <<'JAVA'
import java.net.HttpURLConnection;
import java.net.URI;

final class Healthcheck {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:8080/actuator/health")
                .toURL()
                .openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                System.exit(1);
            }
        } finally {
            connection.disconnect();
        }
    }
}
JAVA
javac --release 17 -d /tmp/healthcheck /tmp/Healthcheck.java
EOF

FROM eclipse-temurin:17.0.20_8-jre-noble AS runtime
WORKDIR /app
ENV SERVER_PORT=8080

RUN mkdir -p /var/lib/finance/receipts /opt/healthcheck \
    && chown -R 10001:10001 /app /var/lib/finance/receipts

COPY --from=build --chown=10001:10001 /workspace/finance-core.jar /app/finance-core.jar
COPY --from=build /tmp/healthcheck/Healthcheck.class /opt/healthcheck/Healthcheck.class

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD ["java", "-cp", "/opt/healthcheck", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/finance-core.jar"]
