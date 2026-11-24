package br.com.consultas.operacoescredito;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * LocalStack só com o SQS, com a mesma imagem e o mesmo script de init do docker-compose: cria a fila dos eventos
 * de consumo. Quem liga o envio para ela é {@link EventosDeConsumoContainers}; sem ele, o evento fica desligado
 * ({@code consultas.eventos.habilitado=false} nos testes).
 */
@TestConfiguration(proxyBeanMethods = false)
public class SqsContainers {

    static final String IMAGEM_LOCALSTACK = "localstack/localstack:4.14.0";

    @Bean
    LocalStackContainer localStack() {
        return new LocalStackContainer(DockerImageName.parse(IMAGEM_LOCALSTACK))
                .withEnv("SERVICES", "sqs")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("localstack/init/ready.d/00-eventos-consumo.sh", 0755),
                        "/etc/localstack/init/ready.d/00-eventos-consumo.sh")
                .waitingFor(Wait.forLogMessage(".*EVENTOS_CONSUMO_PRONTA.*", 1));
    }
}
