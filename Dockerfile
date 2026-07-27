# Build stage: link the frontend to Wasm, then assemble the backend fat jar.
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.5_11_1.10.7_3.6.2 AS build
WORKDIR /build

# Node 26+: Node 24/25 carry a V8 bug that breaks Gears' nested async contexts.
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
# backend/assembly triggers frontend/fullLinkJS through the resourceGenerators
# wiring added in Step 4, so the app is bundled into the jar.
#
# sbt 2 centralises output under target/out/jvm/scala-3.8.4/<project>/, not
# <project>/target/. Rather than hardcode a layout that sbt may reorganise
# again, find the artifact and normalise its name here.
RUN sbt backend/assembly \
 && find target -name 'lm-bot-backend-assembly-*.jar' -print -quit \
      | xargs -I{} cp {} /build/lm-bot.jar \
 && test -s /build/lm-bot.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /build/lm-bot.jar /app/lm-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lm-bot.jar"]
