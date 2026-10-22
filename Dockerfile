FROM amazoncorretto:24
MAINTAINER "Christoph Hüttner (chrhuettner@edu.aau.at)"

WORKDIR /app
COPY target/DependencyConflictResolver-1.0-SNAPSHOT.jar app.jar
COPY Java_Src Java_Src

# Override this at runtime with, for example, `-e JAVA_TOOL_OPTIONS=-Xmx32g` if needed.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java","-jar","/app/app.jar"]
