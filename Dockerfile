# Build stage: link the frontend, then assemble the backend fat jar.
# `2.x` is the sbt 2 image (currently JDK 25.0.4 LTS + sbt 2.0.9); the sbt
# version that actually builds the project still comes from
# project/build.properties.
FROM sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_2.x AS build
WORKDIR /build

# Node 26+: required by Gears' JSPI implementation.
RUN curl -fsSL https://deb.nodesource.com/setup_26.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Dependencies first, so a source-only change does not re-resolve them.
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt ./
RUN sbt update

COPY shared shared
COPY backend backend
COPY frontend frontend

# Build the frontend to Wasm, then assemble the backend fat JAR.
# sbt 2 centralises output under target/out/jvm/scala-3.9.0/<project>/,
# not <project>/target/.  Rather than hardcode a layout that sbt may reorganise
# again, find the artifact and normalise its name here.
#
# Set useFastLinkForAssets to false so the resource generator reads from
# fullLinkJSOutput (the production link stage) rather than fastLinkJSOutput.
RUN sbt 'set ThisBuild/useFastLinkForAssets := false; frontend/fullLinkJS; backend/assembly' \
 && find target -name 'lm-bot-backend-assembly-*.jar' -print -quit \
      | xargs -I{} cp {} /build/lm-bot.jar \
 && test -s /build/lm-bot.jar

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /build/lm-bot.jar /app/lm-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lm-bot.jar"]
