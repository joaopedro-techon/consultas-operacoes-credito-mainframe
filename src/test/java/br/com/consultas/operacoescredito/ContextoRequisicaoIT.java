package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contexto da requisição observado pela API HTTP: {@code x-correlationid} recebido, gerado ou
 * rejeitado, sempre devolvido no header e no Problem Details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ContextoRequisicaoIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_CORRELATION_ID = "x-correlationid";

    private final RestTestClient client;

    @Autowired
    ContextoRequisicaoIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void correlationIdAusenteGeraUmUuidDevolvidoNaResposta() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HEADER_CORRELATION_ID,
                        valor -> assertThat(UUID.fromString(valor).toString()).isEqualTo(valor));
    }

    @Test
    void correlationIdsGeradosSaoDiferentesEntreRequisicoes() {
        var primeiro = correlationIdDevolvidoSemInformar();
        var segundo = correlationIdDevolvidoSemInformar();

        assertThat(primeiro).isNotEqualTo(segundo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "A", "0123456789012345678901234567890123456789012345678901234567890123"})
    void correlationIdValidoEReaproveitado(String correlationId) {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, correlationId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc_123", "abc 123", "abc.123", "a;b",
            "01234567890123456789012345678901234567890123456789012345678901234"})
    void correlationIdInvalidoDevolve400ComUmCorrelationIdNovo(String correlationId) {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HEADER_CORRELATION_ID, valor -> assertThat(UUID.fromString(valor)).isNotNull())
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains(HEADER_CORRELATION_ID, "64", "[A-Za-z0-9-]"))
                .jsonPath("$.correlation_id").value(String.class,
                        valor -> assertThat(UUID.fromString(valor)).isNotNull());
    }

    @Test
    void problemDetailsTrazOCorrelationIdDaRequisicao() {
        client.get().uri(ROTA + "?id_operacao=999999999")
                .header("x-tipo-consulta", "OPERACAO")
                .header(HEADER_CORRELATION_ID, "busca-inexistente-1")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, "busca-inexistente-1")
                .expectBody()
                .jsonPath("$.correlation_id").isEqualTo("busca-inexistente-1");
    }

    @Test
    void erroDeValidacaoDoControllerTambemTrazOCorrelationId() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header(HEADER_CORRELATION_ID, "sem-tipo-1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, "sem-tipo-1")
                .expectBody()
                .jsonPath("$.correlation_id").isEqualTo("sem-tipo-1");
    }

    private String correlationIdDevolvidoSemInformar() {
        return client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(HEADER_CORRELATION_ID);
    }
}
