# To build:
# docker build . --file docker/Dockerfile --tag soklet/toystore

# To run:
# docker run -it -e APP_ENVIRONMENT="local" -p "8080:8080" soklet/toystore

# JDK 25
FROM amazoncorretto:25

ARG MAVEN_VERSION=3.9.11
ARG MAVEN_BASE=apache-maven-${MAVEN_VERSION}

# Let port 8080 be available to the outside world
EXPOSE 8080

ENV APP_RUNNING_IN_DOCKER=true

# 1. Prepare Maven
# We assume the file docker/apache-maven-3.9.11-bin.tar.gz exists locally
RUN yum install -y tar gzip
COPY docker/${MAVEN_BASE}-bin.tar.gz /tmp/maven.tar.gz

# Extract using standard tar command
RUN tar -xzf /tmp/maven.tar.gz -C /opt && \
    rm /tmp/maven.tar.gz

ENV PATH="${PATH}:/opt/${MAVEN_BASE}/bin"

# 2. Prepare dependencies (Layer Caching Strategy)
RUN mkdir -p /app
WORKDIR /app

# Copy ONLY the POM first
COPY pom.xml /app

# Download dependencies/plugins.
# This layer will be CACHED unless pom.xml changes.
RUN mvn dependency:go-offline -B

# 3. Copy source and build
COPY src/main /app/src/main
COPY strings /app/strings
COPY config /app/config

# Build (skip dependency download since we did it above)
RUN mvn compile && \
    mvn dependency:copy-dependencies

# Unprivileged user for runtime
USER 1000

# Run app
CMD ["/bin/sh", "-c", "java -Xms128m -Xmx512m \
    -cp \"target/classes:target/dependency/*\" \
    --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED \
    --add-opens java.base/java.time=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    com.soklet.example.App"]