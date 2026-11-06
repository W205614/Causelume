FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY frontend/public frontend/public
COPY backend/src/main backend/src/main
RUN --mount=type=cache,target=/root/.m2 mvn -q -f backend/pom.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /app
RUN groupadd --system causelume && useradd --system --gid causelume causelume && mkdir /app/data && chown causelume:causelume /app/data
COPY --from=build /src/backend/target/causelume-1.0.0.jar /app/server.jar
USER causelume
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/server.jar"]
