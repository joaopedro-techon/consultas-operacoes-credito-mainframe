package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cota da fonte primária (ADR-0015): esgotadas as permissões do período, a aplicação recusa com 429 e
 * {@code Retry-After} sem chamar o mainframe. Contexto próprio (cota de 1 chamada por segundo numa única task, em
 * períodos de 1 min: 60 permissões) com um único teste, para que nenhum outro consuma as permissões.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.ratelimiter.instances.fontePrimaria.limit-refresh-period=1m",
        "resilience4j.ratelimiter.instances.fontePrimaria.timeout-duration=0s",
        "consultas.fonte-primaria.cota.chamadas-por-segundo=1",
        "consultas.fonte-primaria.cota.max-tasks=1"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class RateLimiterFontePrimariaIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final int PERMISSOES = 60;
    /** Sem expand e sem custódia em cache: operacao nas 4 custódias (descoberta) + originacao na vencedora. */
    private static final int CHAMADAS_POR_CONSULTA = 5;

    private final RestTestClient client;
    private final GatewayMainframeSimulado gateway;

    @Autowired
    RateLimiterFontePrimariaIT(@LocalServerPort int porta, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        // Cliente sem retry automático: o do Apache HttpClient repetiria a recusa depois do Retry-After
        this.client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta)
                .build();
        this.gateway = new GatewayMainframeSimulado(wireMock);
    }

    @Test
    void cotaEsgotadaDevolve429ComRetryAfterSemChamarOMainframe() {
        var id = 920000200L;
        gateway.registrarOperacao(id, 0);

        for (int i = 0; i < PERMISSOES / CHAMADAS_POR_CONSULTA; i++) {
            consultar(id).expectStatus().isOk();
        }
        var resposta = consultar(id)
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "60")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Cota da fonte de dados esgotada")
                .jsonPath("$.correlation_id").exists()
                .returnResult();

        assertThat(gateway.contar(GatewayMainframeSimulado.blocos(id))).isEqualTo(PERMISSOES);
        // 429 do contrato (ADR-0017): Problem Details com Retry-After e x-correlationid
        ContratoOpenApi.assertConforme(resposta);
    }

    private RestTestClient.ResponseSpec consultar(long id) {
        return client.get().uri(ROTA + "?id_operacao=" + id)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
