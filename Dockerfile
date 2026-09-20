# Packages the prebuilt application into a runtime image.
#
# This stage does not compile Scala. The fat jar — with the production
# (full-link) Wasm frontend already bundled as web/ resources — is produced
# outside Docker:
#
#   sbt stageDockerJar     # writes ./lm-bot.jar
#   docker build -t lm-bot .
#
# CI runs the same `sbt stageDockerJar` before `docker build`. `.dockerignore`
# keeps the build context to that one jar, so image builds take seconds and
# reuse the compilation, tests, and linking performed by earlier steps.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY lm-bot.jar /app/lm-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lm-bot.jar"]
