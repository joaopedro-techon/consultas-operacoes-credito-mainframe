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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta do {@code fields} (seleção de campos) na fonte primária.
 * Valores esperados vêm da massa do gateway simulado em {@code wiremock/mappings}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class SelecaoDeCamposIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";

    private final RestTestClient client;

    @Autowired
    SelecaoDeCamposIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void blocoObjetoVemSoComOsCamposPedidosMaisIdOperacaoEBlocoSemCamposVemCompleto() {
        consultar("100000001", "", "operacao.data_contratacao_operacao,operacao.situacao_operacao_credito")
                .expectStatus().isOk()
                .expectBody().json("""
                        {"data": [{
                          "operacao": {
                            "id_operacao": 100000001,
                            "situacao_operacao_credito": "ATIVA",
                            "data_contratacao_operacao": "2026-05-21"
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
    void blocoListaVemComOsCamposPedidosEIdOperacaoEmTodoItem() {
        consultar("100000001", "parcelas,componentes",
                "parcelas.numero_parcela_amortizacao, parcelas.situacao_parcela")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(4)
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(12)
                .jsonPath("$.data[0].parcelas[5]").isEqualTo(Map.of(
                        "id_operacao", 100000001,
                        "numero_parcela_amortizacao", 6,
                        "situacao_parcela", "ABERTA"))
                .jsonPath("$.data[0].parcelas[*].length()").value(List.class, tamanhos ->
                        assertThat(tamanhos).hasSize(12).containsOnly(3))
                .jsonPath("$.data[0].componentes[0].tipo_componente_financeiro").isEqualTo("LIBERACAO")
                .jsonPath("$.data[0].componentes[0].valor_componente_financeiro").isEqualTo(6000.00)
                .jsonPath("$.data[0].operacao.cpf_cnpj").exists();
    }

    @Test
    void campoAninhadoDoSaldoMantemIdOperacaoNoBlocoENosItensAninhados() {
        consultar("100000001", "saldo", "saldo.saldo_parcelas.saldo_devedor")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].saldo.length()").isEqualTo(2)
                .jsonPath("$.data[0].saldo.id_operacao").isEqualTo(100000001)
                .jsonPath("$.data[0].saldo.saldo_parcelas.length()").isEqualTo(12)
                .jsonPath("$.data[0].saldo.saldo_parcelas[5]").isEqualTo(Map.of(
                        "id_operacao", 100000001,
                        "saldo_devedor", 308.00));
    }

    @Test
    void campoAninhadoEmDoisNiveisDoPlano() {
        consultar("100000001", "plano",
                "plano.numero_plano_amortizacao_ativo,plano.dados_plano_amortizacao.encargos_moratorios.dia_fim")
                .expectStatus().isOk()
                .expectBody().json("""
                        {"data": [{"plano": {
                          "id_operacao": 100000001,
                          "numero_plano_amortizacao_ativo": 1,
                          "dados_plano_amortizacao": [
                            {"encargos_moratorios": [{"dia_fim": 30}, {"dia_fim": 9999}]}
                          ]
                        }}]}
                        """, JsonCompareMode.LENIENT)
                .jsonPath("$.data[0].plano.length()").isEqualTo(3)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].length()").isEqualTo(1)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].encargos_moratorios[0].length()").isEqualTo(1);
    }

    @Test
    void campoCompostoSelecionadoInteiroVemCompletoMesmoComSubcampoTambemSelecionado() {
        consultar("100000001", "saldo", "saldo.saldo_parcelas.saldo_devedor,saldo.saldo_parcelas")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].saldo.length()").isEqualTo(2)
                .jsonPath("$.data[0].saldo.saldo_parcelas[5]").isEqualTo(Map.of(
                        "id_operacao", 100000001,
                        "numero_parcela_amortizacao", 6,
                        "numero_plano_amortizacao", 1,
                        "saldo_devedor", 308.00));
    }

    @Test
    void operacaoEOriginacaoComTodosOsCamposExcluidosVoltamSoComIdOperacao() {
        consultar("100000001", "parcelas", "operacao.id_operacao,originacao.id_operacao,parcelas.situacao_parcela")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao").isEqualTo(Map.of("id_operacao", 100000001))
                .jsonPath("$.data[0].originacao").isEqualTo(Map.of("id_operacao", 100000001))
                .jsonPath("$.data[0].parcelas[0].length()").isEqualTo(2);
    }

    @Test
    void campoAnulavelSelecionadoVoltaNulo() {
        consultar("100000001", "", "operacao.data_baixa_operacao")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.length()").isEqualTo(2)
                .jsonPath("$.data[0].operacao.data_baixa_operacao").isEqualTo(null);
    }

    @Test
    void blocoIndisponivelComCamposSelecionadosContinuaNuloNoHeader() {
        consultar("100000003", "saldo,parcelas", "saldo.saldo_devedor_operacao,parcelas.situacao_parcela")
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-blocos-indisponiveis", "saldo")
                .expectBody()
                .jsonPath("$.data[0].saldo").isEqualTo(null)
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(24)
                .jsonPath("$.data[0].parcelas[0].length()").isEqualTo(2);
    }

    @Test
    void fieldsVazioDevolveBlocosCompletos() {
        consultar("100000001", "", "")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.length()").isEqualTo(6)
                .jsonPath("$.data[0].originacao.length()").isEqualTo(3);
    }

    @ParameterizedTest(name = "fields={1} com expand={0} -> 400")
    @CsvSource(delimiter = '|', value = {
            "parcelas | parcelas.numero_parcela",
            "''       | operacao.inexistente",
            "saldo    | saldo.saldo_parcelas.inexistente",
            "''       | operacao.cpf_cnpj.digito",
            "''       | operacao.",
            "''       | operacao",
            "''       | .id_operacao",
            "''       | 'operacao.cpf_cnpj,,originacao.canal_contratacao'",
            "''       | garantias.valor",
            "''       | OPERACAO.cpf_cnpj"
    })
    void campoMalformadoOuForaDoCatalogoDevolve400(String expand, String fields) {
        consultar("100000001", expand, fields)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("fields"));
    }

    @Test
    void campoDesconhecidoDevolve400ListandoOsCamposValidosDoBloco() {
        consultar("100000001", "saldo", "saldo.saldo_total")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("saldo.saldo_total", "saldo.saldo_devedor_operacao",
                                "saldo.saldo_parcelas.saldo_devedor"));
    }

    @Test
    void campoDeBlocoForaDoExpandDevolve400SemExpansaoImplicita() {
        consultar("100000001", "saldo", "parcelas.situacao_parcela")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("parcelas", "expand"));
    }

    @Test
    void fieldsInvalidoDevolve400MesmoParaOperacaoInexistente() {
        consultar("999999999", "", "operacao.inexistente")
                .expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec consultar(String idOperacao, String expand, String fields) {
        return client.get().uri(ROTA + "?id_operacao={id}&expand={expand}&fields={fields}", idOperacao, expand, fields)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
