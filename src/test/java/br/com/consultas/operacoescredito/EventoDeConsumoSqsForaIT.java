package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SQS fora do ar (ADR-0004): o endpoint do SQS é um socket que aceita a conexão e nunca responde, então cada
 * envio só termina pelo timeout do SDK. Com fila em memória de 3 eventos e um envio por vez (bulkhead), a fila
 * enche: o evento é descartado e contado em {@code eventos.consumo.descartados}, e a consulta segue com a mesma
 * resposta e sem esperar o SQS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.eventos.habilitado=true",
        "consultas.eventos.segredo-hmac=segredo-de-teste-com-pelo-menos-32-bytes",
        "consultas.eventos.fila.capacidade=3",
        "consultas.eventos.fila.intervalo-envio=50ms",
        "consultas.eventos.fila.tempo-maximo-drenagem=1s",
        "consultas.eventos.sqs.timeout-tentativa=3s",
        "consultas.eventos.sqs.timeout-chamada=3s",
        "consultas.eventos.sqs.credenciais.access-key-id=test",
        "consultas.eventos.sqs.credenciais.secret-access-key=test",
        "resilience4j.bulkhead.instances.sqs.max-concurrent-calls=1",
        "resilience4j.bulkhead.instances.sqs.max-wait-duration=30s"
})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class EventoDeConsumoSqsForaIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** SQS que aceita conexões (o SO completa o handshake) e nunca responde. */
    private static final ServerSocket SQS_MUDO = abrirSocket();

    private final RestTestClient metricas;

    @LocalServerPort
    private int porta;

    @Autowired
    EventoDeConsumoSqsForaIT(RestTestClient metricas) {
        this.metricas = metricas;
    }

    @DynamicPropertySource
    static void sqsMudo(DynamicPropertyRegistry registry) {
        var endpoint = "http://localhost:" + SQS_MUDO.getLocalPort();
        registry.add("consultas.eventos.sqs.endpoint", () -> endpoint);
        registry.add("consultas.eventos.sqs.url-fila", () -> endpoint + "/000000000000/consultas-eventos-consumo");
    }

    @AfterAll
    static void fecharSocket() throws IOException {
        SQS_MUDO.close();
    }

    @Test
    void sqsForaDescartaEventosSemAfetarAConsulta() {
        // Sem retry do cliente de teste em 503: cada chamada é uma requisição só
        var client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta).build();
        // Aquecimento: a primeira chamada abre conexões com o gateway do mainframe
        var esperado = consultar(client);

        for (int i = 0; i < 10; i++) {
            var inicio = System.nanoTime();
            var corpo = consultar(client);
            var duracao = Duration.ofNanos(System.nanoTime() - inicio);

            assertThat(corpo).isEqualTo(esperado);
            // Cada envio ao SQS mudo leva 3 s: a consulta não espera por ele
            assertThat(duracao).isLessThan(Duration.ofSeconds(2));
        }

        await().atMost(Duration.ofSeconds(10))
                .until(() -> descartados("fila_cheia") > 0);
        // O envio preso no SQS mudo termina por timeout e o lote também é descartado
        await().atMost(Duration.ofSeconds(20))
                .until(() -> descartados("falha_envio") > 0);
    }

    private static String consultar(RestTestClient client) {
        return client.get().uri(ROTA + "?id_operacao=100000001&expand=parcelas")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
    }

    private double descartados(String motivo) {
        var resultado = metricas.get().uri("/actuator/metrics/eventos.consumo.descartados?tag=motivo:" + motivo)
                .exchange()
                .returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return 0.0;
        }
        for (JsonNode medida : JSON.readTree(resultado.getResponseBody()).path("measurements")) {
            if ("COUNT".equals(medida.path("statistic").asString())) {
                return medida.path("value").asDouble();
            }
        }
        return 0.0;
    }

    private static ServerSocket abrirSocket() {
        try {
            return new ServerSocket(0, 100);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
