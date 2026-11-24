package br.com.consultas.operacoescredito;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Liga a publicação do evento de consumo ({@code consultas.eventos.habilitado=true}, desligada nos demais testes)
 * apontando para a fila criada pelo init do LocalStack de {@link SqsContainers}
 * ({@code localstack/init/ready.d/00-eventos-consumo.sh}). Envio a cada 100 ms, para os testes não esperarem 1 s.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EventosDeConsumoContainers {

    static final String FILA = "consultas-eventos-consumo";
    static final String SEGREDO_HMAC = "segredo-de-teste-do-hmac-com-32-bytes-ou-mais";

    @Bean
    DynamicPropertyRegistrar eventosDeConsumoProperties(LocalStackContainer localStack) {
        return registry -> {
            registry.add("consultas.eventos.habilitado", () -> "true");
            registry.add("consultas.eventos.segredo-hmac", () -> SEGREDO_HMAC);
            registry.add("consultas.eventos.fila.intervalo-envio", () -> "100ms");
            registry.add("consultas.eventos.sqs.endpoint", () -> localStack.getEndpoint().toString());
            registry.add("consultas.eventos.sqs.url-fila", () -> urlFila(localStack));
            registry.add("consultas.eventos.sqs.regiao", localStack::getRegion);
            registry.add("consultas.eventos.sqs.credenciais.access-key-id", localStack::getAccessKey);
            registry.add("consultas.eventos.sqs.credenciais.secret-access-key", localStack::getSecretKey);
        };
    }

    static String urlFila(LocalStackContainer localStack) {
        return localStack.getEndpoint() + "/000000000000/" + FILA;
    }

    /** Cliente SQS do teste, para ler a fila direto no LocalStack. */
    static SqsClient sqs(LocalStackContainer localStack) {
        return SqsClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                .build();
    }

    /** HMAC-SHA256 esperado (hex minúsculo) com o segredo dos testes. */
    static String hmac(String valor) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SEGREDO_HMAC.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
