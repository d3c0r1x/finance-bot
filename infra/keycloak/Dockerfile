FROM eclipse-temurin:17.0.20_8-jdk-noble AS healthcheck-build
WORKDIR /src
COPY ops/keycloak/Healthcheck.java ./Healthcheck.java
RUN mkdir -p /out \
    && javac --release 11 -d /out /src/Healthcheck.java

FROM quay.io/keycloak/keycloak:26.7.5
COPY --from=healthcheck-build --chown=1000:0 /out/Healthcheck.class /opt/keycloak/Healthcheck.class
