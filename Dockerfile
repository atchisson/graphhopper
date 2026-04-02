# syntax=docker/dockerfile:1.6
FROM maven:3-eclipse-temurin-17 AS builder

WORKDIR /graphhopper

# Copy full sources and build the web module (downloads deps)
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl web -am package

# Runtime image
FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /graphhopper

COPY --from=builder /graphhopper/web/target/graphhopper-web-*.jar web/target/
COPY --from=builder /graphhopper/config-example.yml config-example.yml
COPY --from=builder /graphhopper/core/src/main/resources/com/graphhopper/custom_models /graphhopper/custom_models
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENV JAVA_OPTS="-Xmx4g" \
    OSM_REGION="centre" \
    DATA_DIR="/data" \
    GRAPH_DIR="/data/graph-cache" \
    CUSTOM_MODELS_DIR="/graphhopper/custom_models"

VOLUME ["/data"]

EXPOSE 8989 8990

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
