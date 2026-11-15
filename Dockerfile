FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache bash
COPY target/knowledge-forge-documentations.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]