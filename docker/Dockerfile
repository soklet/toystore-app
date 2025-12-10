# To build:
# docker build . --file docker/Dockerfile --tag soklet/toystore

# To run (use Ctrl+C to stop):
# docker run -e APP_ENVIRONMENT="local" -p 8080:8080 -p 8081:8081 soklet/toystore

# Build stage
FROM amazoncorretto:25 AS builder
ARG MAVEN_VERSION=3.9.11
RUN yum install -y tar gzip && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xzC /opt
ENV PATH="${PATH}:/opt/apache-maven-${MAVEN_VERSION}/bin"

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src/main src/main
RUN mvn compile && mvn dependency:copy-dependencies

# Runtime stage
FROM amazoncorretto:25
ENV APP_RUNNING_IN_DOCKER=true
RUN yum clean all
WORKDIR /app
COPY --from=builder /app/target/classes target/classes
COPY --from=builder /app/target/dependency target/dependency
COPY strings strings
COPY config config
COPY web web

EXPOSE 8080 8081
USER 1000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health-check || exit 1

CMD ["sh", "-c", "exec java -Xms128m -Xmx512m \
    -cp 'target/classes:target/dependency/*' \
    --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED \
    --add-opens java.base/java.time=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    com.soklet.toystore.App"]