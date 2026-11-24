# Build: os testes rodam no CI (precisam de Docker para o Testcontainers), não na imagem.
# Imagens fixadas por tag exata e digest (atualizar os dois juntos)
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:3c35c595580ae3342eb34e5832e8e516ded8e767efa4a5fe61b8644d44847b8e AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests \
    && java -Djarmode=tools -jar target/consultas-operacoes-credito-mainframe-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:23481dc33a759b79d62814ceb1ec27a0d1a84da9d7aab0609577427176fbb1b7
# Usuário sem privilégios com UID/GID numéricos: USER numérico permite ao runtime (ex.: runAsNonRoot do
# Kubernetes) verificar que o processo não roda como root
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# Sem memória, o processo termina (e o orquestrador reinicia o container) em vez de seguir num estado incerto
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
