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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta do {@code expand} na consulta OPERACAO da fonte primária.
 * Valores esperados vêm da massa do gateway simulado em {@code wiremock/mappings}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ExpandBlocosIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";

    private final RestTestClient client;

    @Autowired
    ExpandBlocosIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void blocoPedidoNoExpandVemJuntoComOperacaoEOriginacao() {
        consultar("100000001", "parcelas")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].length()").isEqualTo(3)
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(100000001)
                .jsonPath("$.data[0].originacao.canal_contratacao").isEqualTo("MOBILE")
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(12)
                .jsonPath("$.data[0].parcelas[5].numero_parcela_amortizacao").isEqualTo(6)
                .jsonPath("$.data[0].parcelas[5].data_vencimento_parcela").isEqualTo("2026-11-21")
                .jsonPath("$.data[0].parcelas[5].valor_principal_parcela").isEqualTo(500.00)
                .jsonPath("$.data[0].parcelas[5].valor_juros_principal_parcela").isEqualTo(108.00)
                .jsonPath("$.data[0].parcelas[5].situacao_parcela").isEqualTo("ABERTA")
                .jsonPath("$.data[0].parcelas[4].situacao_parcela").isEqualTo("LIQUIDADA");
    }

    @Test
    void todosOsBlocosPodemSerPedidosJuntosENomeDoExpandEhAChaveDoJson() {
        consultar("100000001", "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(9)
                .jsonPath("$.data[0].componentes[0].tipo_componente_financeiro").isEqualTo("LIBERACAO")
                .jsonPath("$.data[0].componentes[0].valor_componente_financeiro").isEqualTo(6000.00)
                .jsonPath("$.data[0].amortizacoes.length()").isEqualTo(6)
                .jsonPath("$.data[0].amortizacoes[5].valor_principal_amortizado").isEqualTo(192.00)
                .jsonPath("$.data[0].amortizacoes[5].indicador_ocorrencia_valida").isEqualTo(true)
                .jsonPath("$.data[0].recebimentos[5].valor_recebido").isEqualTo(300.00)
                .jsonPath("$.data[0].recebimentos[5].tipo_baixa").isEqualTo("BAIXA_PARCIAL_PARCELA")
                .jsonPath("$.data[0].plano.numero_plano_amortizacao_ativo").isEqualTo(1)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].taxa_juros_nominal").isEqualTo(1.80)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].encargos_moratorios[1].dia_fim").isEqualTo(9999)
                .jsonPath("$.data[0].saldo.saldo_devedor_operacao").isEqualTo(3956.00)
                .jsonPath("$.data[0].saldo.quantidade_dias_atraso").isEqualTo(2)
                .jsonPath("$.data[0].saldo.saldo_parcelas[5].saldo_devedor").isEqualTo(308.00)
                .jsonPath("$.data[0].controles_iof[?(@.numero_parcela_amortizacao == 6)].numero_sequencial_controle_iof")
                .isEqualTo(List.of(1, 2))
                .jsonPath("$.data[0].controles_iof[?(@.numero_parcela_amortizacao == 6)].valor_iof_devido")
                .isEqualTo(List.of(2.90, 4.64))
                .jsonPath("$.data[0].controles_iof[?(@.numero_parcela_amortizacao == 6)].valor_iof_pago")
                .isEqualTo(List.of(2.90, 0.00))
                .jsonPath("$.data[0].controles_iof[?(@.numero_parcela_amortizacao == 6)].data_pagamento_iof")
                .isEqualTo(Arrays.asList("2026-11-10", null));
    }

    @Test
    void controlesIofTrazemOsCamposDoBlocoNove() {
        consultar("100000002", "controles_iof")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(3)
                .jsonPath("$.data[0].controles_iof[0]").isEqualTo(Map.of(
                        "id_operacao", 100000002,
                        "numero_parcela_amortizacao", 1,
                        "numero_plano_amortizacao", 1,
                        "numero_sequencial_controle_iof", 1,
                        "valor_iof_devido", 0.74,
                        "valor_iof_pago", 0.74,
                        "data_pagamento_iof", "2025-10-15"));
    }

    @Test
    void operacaoEOriginacaoNoExpandNaoDuplicamNemRemovemBlocos() {
        consultar("100000004", "operacao,originacao,operacao")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(2)
                .jsonPath("$.data[0].operacao.cpf_cnpj").isEqualTo("12ABC34501DE35")
                .jsonPath("$.data[0].originacao.dependencia_contabil").isEqualTo("031");
    }

    @Test
    void espacosEntreOsBlocosDoExpandSaoIgnorados() {
        consultar("100000001", " saldo , parcelas ")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(4)
                .jsonPath("$.data[0].saldo.id_operacao").isEqualTo(100000001)
                .jsonPath("$.data[0].parcelas[0].id_operacao").isEqualTo(100000001);
    }

    @Test
    void expandVazioDevolveSoOsBlocosQueVemSempre() {
        consultar("100000001", "")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(2)
                .jsonPath("$.data[0].operacao").exists()
                .jsonPath("$.data[0].originacao").exists();
    }

    @Test
    void blocoPedidoEAusenteNaFontePrimariaVemNuloEListadoNoHeader() {
        consultar("100000003", "saldo,parcelas,plano,recebimentos")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo")
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(6)
                .jsonPath("$.data[0].saldo").isEqualTo(null)
                .jsonPath("$.data[0].plano").isEqualTo(null)
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(24)
                .jsonPath("$.data[0].recebimentos").isEmpty();
    }

    @Test
    void blocoAusenteQueNaoFoiPedidoNaoGeraHeader() {
        consultar("100000003", "parcelas")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[0].saldo").doesNotExist()
                .jsonPath("$.data[0].plano").doesNotExist();
    }

    @ParameterizedTest(name = "expand={0} -> 400")
    @ValueSource(strings = {"garantias", "parcelas,garantias", "PARCELAS", "parcelas,,saldo", "parcela"})
    void blocoDesconhecidoDevolve400ListandoOsBlocosValidos(String expand) {
        consultar("100000001", expand)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("expand", "operacao", "originacao", "componentes", "parcelas", "amortizacoes",
                                "recebimentos", "plano", "saldo", "controles_iof"));
    }

    private RestTestClient.ResponseSpec consultar(String idOperacao, String expand) {
        return client.get().uri(ROTA + "?id_operacao={id}&expand={expand}", idOperacao, expand)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
