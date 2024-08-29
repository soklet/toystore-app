# To build:
# docker build . --file docker/Dockerfile --tag soklet/toystore

# To run:
# docker run -it -e APP_ENVIRONMENT="local" -p "8080:8080" soklet/toystore

# JDK 21
FROM amazoncorretto:21

# Let port 8080 be available to the outside world
EXPOSE 8080

# This environment variable lets the app
# know that it's running inside of a Docker container
# in case it needs to behave specially there.
# The name and value are arbitrary
ENV APP_RUNNING_IN_DOCKER=true

# Prepare Maven
RUN yum install -y unzip
ENV PATH="${PATH}:/opt/apache-maven-3.9.7/bin"
WORKDIR /opt
COPY docker/apache-maven-3.9.7-bin.zip /opt
RUN unzip apache-maven-3.9.7-bin.zip

# Prepare app
RUN mkdir -p /app/src
WORKDIR /app

# Copy in source files
COPY pom.xml /app
COPY src/main /app/src/main
COPY strings /app/strings
COPY config /app/config

# Build app and copy dependency JARs into position
RUN mvn compile
RUN mvn dependency:copy-dependencies

# Unprivileged user for runtime
USER 1000

# Run app
CMD ["/bin/sh", "-c", "java -Xms128m -Xmx512m -cp \"target/classes:target/dependency/*\" --enable-preview --add-opens java.base/java.time=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED com.soklet.example.App"]