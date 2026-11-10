FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY frontend/public frontend/public
COPY backend/src/main backend/src/main
RUN --mount=type=cache,target=/root/.m2 mvn -q -f backend/pom.xml -Dmaven.test.skip=true package dependency:list -DincludeScope=runtime -DoutputFile=/src/runtime-dependencies.txt

FROM eclipse-temurin:21-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
# Alpine's fixed zlib closes CVE-2026-4738.
RUN apk add --no-cache --upgrade zlib=1.4-r0
WORKDIR /app
# Preserve the numeric identity that owns existing H2 volumes.
RUN mkdir /app/data && chown 999:999 /app/data
COPY --from=build /src/backend/target/causelume-1.0.3.jar /app/server.jar
COPY LICENSE THIRD_PARTY_NOTICES.md deployment/licenses/SOURCES.md /usr/share/causelume/licenses/
COPY --from=build /src/runtime-dependencies.txt /usr/share/causelume/licenses/java-dependencies.txt
RUN cp /lib/apk/db/installed /usr/share/causelume/licenses/alpine-packages.txt
LABEL org.opencontainers.image.source="https://github.com/W205614/Causelume"
USER 999:999
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/server.jar"]
