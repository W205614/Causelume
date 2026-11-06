FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
# Ubuntu's fixed APT build closes CVE-2026-109518; secure the update transport too.
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g; s|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list \
 && apt-get -o Acquire::Retries=0 update \
 && apt-get -o Acquire::Retries=0 install -y --no-install-recommends --only-upgrade apt=2.4.15ubuntu0.1 libapt-pkg6.0=2.4.15ubuntu0.1 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /var/log/causelume && chown 65532:65532 /var/log/causelume
COPY --from=build /src/target/observation-example-1.0.jar /app/example.jar
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/example.jar"]
