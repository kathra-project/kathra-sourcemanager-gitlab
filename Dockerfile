FROM maven:3-openjdk-15-slim AS build
COPY src /usr/src/app/src
COPY pom.xml /usr/src/app
RUN mvn -f /usr/src/app/pom.xml install
RUN mvn -f /usr/src/app/pom.xml dependency:copy-dependencies

FROM adoptopenjdk:15-jre-hotspot-bionic

COPY --from=build /usr/src/app/target/jar-dependencies/* /deployments/java/
COPY --from=build /usr/src/app/target/*.jar /deployments/java/

EXPOSE 8080

ENTRYPOINT exec java $JAVA_OPTS -cp "/deployments/java/*" org.apache.camel.cdi.Main
