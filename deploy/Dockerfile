# Multi-stage build: frontend (Node) + backend (Maven) into one runtime image serving UI and API from one origin.
# Build context is the repository root: docker build -f deploy/Dockerfile --build-arg AEGIS_COMMIT=<sha> .

FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend
ARG AEGIS_COMMIT=unknown
WORKDIR /build/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY backend/ ./
COPY --from=frontend /build/frontend/dist /build/frontend/dist
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q -Pfrontend -Dfrontend.dist=/build/frontend/dist \
    -Daegis.build.commit=${AEGIS_COMMIT} -DskipTests -DskipITs package \
    && cp bootstrap/target/aegis-bootstrap-*.jar /build/aegis.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S aegis && adduser -S -G aegis -u 10001 aegis \
    && mkdir -p /srv/aegis/ingress /srv/aegis/exports && chown -R aegis:aegis /srv/aegis
USER aegis
WORKDIR /srv/aegis
COPY --from=backend /build/aegis.jar /srv/aegis/aegis.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/srv/aegis/aegis.jar"]
