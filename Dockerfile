# syntax=docker/dockerfile:1.6
FROM maven:3-eclipse-temurin-17 AS builder

WORKDIR /graphhopper

# Copy full sources and build the web module (downloads deps)
COPY . .
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip python3-venv && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/panoramax-venv
ENV PATH="/opt/panoramax-venv/bin:${PATH}"
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl web -am package
RUN pip install --no-cache-dir pyarrow shapely h3 mapbox-vector-tile

# Runtime image
FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates python3 python3-pip python3-venv && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/panoramax-venv
ENV PATH="/opt/panoramax-venv/bin:${PATH}"
RUN pip install --no-cache-dir pyarrow shapely h3 mapbox-vector-tile

WORKDIR /graphhopper

COPY --from=builder /graphhopper/web/target/graphhopper-web-*.jar web/target/
COPY --from=builder /graphhopper/config-example.yml config-example.yml
COPY --from=builder /graphhopper/core/src/main/resources/com/graphhopper/custom_models /graphhopper/custom_models
COPY tools/panoramax_preprocess.py /usr/local/bin/panoramax_preprocess.py
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /usr/local/bin/panoramax_preprocess.py

ENV JAVA_OPTS="-Xmx4g" \
    OSM_REGION="centre" \
    DATA_DIR="/data" \
    GRAPH_DIR="/data/graph-cache" \
    CUSTOM_MODELS_DIR="/graphhopper/custom_models"

VOLUME ["/data"]

EXPOSE 8989 8990

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
