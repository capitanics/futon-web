# futon-web: SPA build + Ktor fat runtime in one image

FROM node:24-alpine AS web
WORKDIR /app
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS server
WORKDIR /app
COPY server/ ./
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=server /app/build/install/futon-web-server/ ./
COPY --from=web /app/dist/ ./web/
ENV PORT=8081 \
    WEB_DIST=/app/web \
    DB_PATH=/data/futon-web.db \
    IMAGE_CACHE_DIR=/data/image-cache
VOLUME /data
EXPOSE 8081
CMD ["./bin/futon-web-server"]
