FROM openjdk:11-jdk-slim
EXPOSE 8080

COPY target/jar-dependencies/* /deployments/java/
COPY target/*.jar /deployments/java/

ENTRYPOINT exec java $JAVA_OPTS -cp "/deployments/java/*" org.apache.camel.cdi.Main
