FROM eclipse-temurin:21.0.4_7-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test -x integrationTest

FROM eclipse-temurin:21.0.4_7-jre-jammy AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system spring && \
    useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring

WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/build/libs/*.jar /app/app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
