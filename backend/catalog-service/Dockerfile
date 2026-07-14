FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S -u 10001 appuser -G appgroup
WORKDIR /app
ARG JAR_FILE=build/libs/*SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
