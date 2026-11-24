package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta da consulta OPERACAO na fonte primária: só a API HTTP é observada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ConsultaOperacaoIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";

    private final RestTestClient client;

    @Autowired
    ConsultaOperacaoIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void consultaPorOperacaoDevolveOsBlocosOperacaoEOriginacao() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().json("""
                        {"data": [{
                          "operacao": {
                            "id_operacao": 100000001,
                            "situacao_operacao_credito": "ATIVA",
                            "data_contratacao_operacao": "2026-05-21",
                            "data_baixa_operacao": null,
                            "cpf_cnpj": "52998224725",
                            "custodia": "SF"
                          },
                          "originacao": {
                            "id_operacao": 100000001,
                            "canal_contratacao": "MOBILE",
                            "dependencia_contabil": "004"
                          }
                        }]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void operacaoInexistenteDevolve404EmProblemDetails() {
        client.get().uri(ROTA + "?id_operacao=999999999")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Operação de crédito não encontrada")
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("999999999"));
    }

    /** A visão consolidada roda em outro serviço (ADR-0017): aqui o caminho dela não existe. */
    @Test
    void rotaDaVisaoConsolidadaNaoExisteNesteServicoEDevolve404EmProblemDetails() {
        client.get().uri("/v1/operacoes-credito?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void headerTipoConsultaAusenteDevolve400ListandoOsTiposValidos() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("x-tipo-consulta", "OPERACAO", "CLIENTE", "CONTA"));
    }

    @Test
    void headerTipoConsultaInvalidoDevolve400ListandoOsTiposValidos() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "CONTRATO")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("x-tipo-consulta", "OPERACAO", "CLIENTE", "CONTA")
                        // o valor recebido não é ecoado (pode ser qualquer coisa, inclusive dado pessoal)
                        .doesNotContain("CONTRATO"));
    }

    @ParameterizedTest(name = "{0} com {1} -> 400")
    @CsvSource(delimiter = '|', textBlock = """
            OPERACAO | ''                                          | id_operacao
            OPERACAO | id_operacao=                                | id_operacao
            OPERACAO | id_operacao=12AB                            | id_operacao
            OPERACAO | id_operacao=-5                              | id_operacao
            OPERACAO | id_operacao=0                               | id_operacao
            OPERACAO | id_operacao=100000001&cpf_cnpj=52998224725  | cpf_cnpj
            OPERACAO | id_operacao=100000001&conta=434115000065874 | conta
            OPERACAO | id_operacao=100000001&limite_operacoes=10   | limite_operacoes
            CLIENTE  | id_operacao=100000001                       | id_operacao
            CLIENTE  | ''                                          | cpf_cnpj
            CONTA    | cpf_cnpj=52998224725                        | cpf_cnpj
            CONTA    | conta=434115000065874&id_operacao=100000001 | id_operacao
            """)
    void parametroIncoerenteComOTipoDeConsultaDevolve400(String tipoConsulta, String query, String parametro) {
        client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipoConsulta)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains(parametro));
    }
}
