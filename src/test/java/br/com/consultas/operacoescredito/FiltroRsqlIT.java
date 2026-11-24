package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta do {@code filter} (RSQL) na consulta OPERACAO da fonte primária.
 * Valores esperados vêm da massa do gateway simulado em {@code wiremock/mappings}:
 * a operação 100000001 (ATIVA, MOBILE) tem 12 parcelas mensais a partir de 2026-06-21, 1 a 5 LIQUIDADA e
 * 6 a 12 ABERTA; 6 amortizações; 4 componentes (LIBERACAO 6000.00, IOF 97.68, IOF_FLAT 22.80, SEGURO 120.00);
 * saldo com 12 saldo_parcelas, 1 a 5 zeradas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class FiltroRsqlIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String OPERACAO = "100000001";

    private final RestTestClient client;

    @Autowired
    FiltroRsqlIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void filtroEmParcelasMantemSoAsParcelasQueAtendemAoCriterio() {
        consultar(OPERACAO, "parcelas",
                "parcelas.numero_parcela_amortizacao=ge=4;parcelas.situacao_parcela==ABERTA")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas[*].numero_parcela_amortizacao")
                .isEqualTo(List.of(6, 7, 8, 9, 10, 11, 12));
    }

    static Stream<Arguments> filtrosDeParcelas() {
        return Stream.of(
                Arguments.of("parcelas.numero_parcela_amortizacao==3", List.of(3)),
                Arguments.of("parcelas.numero_parcela_amortizacao!=3;parcelas.numero_parcela_amortizacao=le=4",
                        List.of(1, 2, 4)),
                Arguments.of("parcelas.numero_parcela_amortizacao=lt=3", List.of(1, 2)),
                Arguments.of("parcelas.numero_parcela_amortizacao<3", List.of(1, 2)),
                Arguments.of("parcelas.numero_parcela_amortizacao=gt=10", List.of(11, 12)),
                Arguments.of("parcelas.numero_parcela_amortizacao=in=(2,5,99)", List.of(2, 5)),
                Arguments.of("parcelas.numero_parcela_amortizacao=out=(1,2,3,4,5,6,7,8,9,10)", List.of(11, 12)),
                Arguments.of("parcelas.numero_parcela_amortizacao==1,parcelas.numero_parcela_amortizacao==12",
                        List.of(1, 12)),
                Arguments.of("(parcelas.numero_parcela_amortizacao==1,parcelas.numero_parcela_amortizacao==12);"
                        + "parcelas.situacao_parcela==ABERTA", List.of(12)),
                Arguments.of("parcelas.situacao_parcela=in=(LIQUIDADA);parcelas.numero_parcela_amortizacao=ge=4",
                        List.of(4, 5)),
                Arguments.of("parcelas.data_vencimento_parcela=lt=2026-08-21", List.of(1, 2)),
                Arguments.of("parcelas.data_vencimento_parcela==2027-05-21", List.of(12)),
                Arguments.of("parcelas.valor_principal_parcela==500", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)),
                Arguments.of("parcelas.valor_principal_parcela=gt=500.00", List.of()),
                Arguments.of("parcelas.situacao_parcela==abertA", List.of()));
    }

    @ParameterizedTest(name = "filter={0}")
    @MethodSource("filtrosDeParcelas")
    void operadoresEComparacoesTipadasPeloCatalogo(String filter, List<Integer> parcelasEsperadas) {
        consultar(OPERACAO, "parcelas", filter)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].parcelas[*].numero_parcela_amortizacao").isEqualTo(parcelasEsperadas);
    }

    @Test
    void decimalComparaPeloValorNumerico() {
        consultar(OPERACAO, "componentes", "componentes.valor_componente_financeiro=ge=97.680;"
                + "componentes.valor_componente_financeiro=lt=6000")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].componentes[*].tipo_componente_financeiro").isEqualTo(List.of("IOF", "SEGURO"));
    }

    @Test
    void dataHoraComparaCronologicamente() {
        consultar(OPERACAO, "amortizacoes", "amortizacoes.data_valor_movimento=gt=2026-10-21T06:00:00")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].amortizacoes[*].numero_parcela_amortizacao").isEqualTo(List.of(6));
    }

    @Test
    void booleanoComparaPorIgualdade() {
        consultar(OPERACAO, "amortizacoes", "amortizacoes.indicador_ocorrencia_valida==true")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].amortizacoes.length()").isEqualTo(6);
        consultar(OPERACAO, "amortizacoes", "amortizacoes.indicador_ocorrencia_valida==false")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].amortizacoes").isEmpty();
    }

    @Test
    void campoNuloSoAtendeADiferenteEFora() {
        consultar(OPERACAO, "controles_iof", "controles_iof.data_pagamento_iof=ge=2026-10-01")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].controles_iof[*].numero_parcela_amortizacao").isEqualTo(List.of(5, 6));
        consultar(OPERACAO, "controles_iof",
                "controles_iof.data_pagamento_iof!=2026-11-10;controles_iof.numero_parcela_amortizacao=ge=6")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].controles_iof[*].numero_parcela_amortizacao")
                .isEqualTo(List.of(6, 7, 8, 9, 10, 11, 12))
                .jsonPath("$.data[0].controles_iof[0].numero_sequencial_controle_iof").isEqualTo(2);
    }

    @Test
    void filtroEmUmBlocoNaoCascateiaParaOsOutros() {
        consultar(OPERACAO, "parcelas,amortizacoes,saldo", "parcelas.situacao_parcela==ABERTA")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(7)
                .jsonPath("$.data[0].amortizacoes.length()").isEqualTo(6)
                .jsonPath("$.data[0].saldo.saldo_parcelas.length()").isEqualTo(12)
                .jsonPath("$.data[0].saldo.saldo_devedor_operacao").isEqualTo(3956.00);
    }

    @Test
    void filtrosEmBlocosDiferentesLigadosPorEFiltramCadaUmOSeu() {
        consultar(OPERACAO, "parcelas,amortizacoes",
                "parcelas.numero_parcela_amortizacao==1;amortizacoes.numero_parcela_amortizacao=ge=5")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas[*].numero_parcela_amortizacao").isEqualTo(List.of(1))
                .jsonPath("$.data[0].amortizacoes[*].numero_parcela_amortizacao").isEqualTo(List.of(5, 6));
    }

    @Test
    void filtroEmListaAninhadaDeBlocoObjetoFiltraSoAquelaLista() {
        consultar(OPERACAO, "saldo", "saldo.saldo_parcelas.saldo_devedor=gt=0")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].saldo.saldo_parcelas[*].numero_parcela_amortizacao")
                .isEqualTo(List.of(6, 7, 8, 9, 10, 11, 12))
                .jsonPath("$.data[0].saldo.saldo_devedor_operacao").isEqualTo(3956.00)
                .jsonPath("$.data[0].saldo.quantidade_dias_atraso").isEqualTo(2);
    }

    @Test
    void listaDentroDoItemAlvoAtendeQuandoAlgumItemAtende() {
        consultar(OPERACAO, "plano", "plano.dados_plano_amortizacao.encargos_moratorios.dia_fim==9999")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].plano.dados_plano_amortizacao.length()").isEqualTo(1)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].encargos_moratorios.length()").isEqualTo(2);
        consultar(OPERACAO, "plano", "plano.dados_plano_amortizacao.encargos_moratorios.dia_fim=gt=9999")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].plano.dados_plano_amortizacao").isEmpty()
                .jsonPath("$.data[0].plano.numero_plano_amortizacao_ativo").isEqualTo(1);
    }

    @Test
    void filtroEmOperacaoQueNaoAtendeRemoveAOperacaoInteira() {
        consultar(OPERACAO, "parcelas", "operacao.situacao_operacao_credito==LIQUIDADA")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void filtroEmOperacaoQueAtendeMantemAOperacaoIntacta() {
        consultar(OPERACAO, "parcelas", "operacao.situacao_operacao_credito==ATIVA;"
                + "operacao.data_contratacao_operacao=ge=2026-01-01")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(12);
    }

    @Test
    void filtroPelaCustodiaDaOperacao() {
        // 100000001 está na custódia SF (campo preenchido pela aplicação, ADR-0012)
        consultar(OPERACAO, "", "operacao.custodia==SF")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("SF");
        consultar(OPERACAO, "", "operacao.custodia=in=(NSF,F5,OD)")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void filtroEmOriginacaoQueNaoAtendeRemoveAOperacaoInteira() {
        consultar(OPERACAO, "", "originacao.canal_contratacao=out=(MOBILE,AGENCIA)")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isEmpty();
        consultar(OPERACAO, "", "originacao.canal_contratacao==MOBILE")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void ouEntreOperacaoEOriginacaoFiltraAOperacao() {
        consultar(OPERACAO, "", "operacao.situacao_operacao_credito==INEXISTENTE,originacao.canal_contratacao==MOBILE")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);
        consultar(OPERACAO, "", "operacao.situacao_operacao_credito==INEXISTENTE,originacao.canal_contratacao==AGENCIA")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void filterRepetidoDevolve400EmVezDeVirarOu() {
        client.get().uri(ROTA + "?id_operacao={id}&expand=parcelas&filter={a}&filter={b}", OPERACAO,
                        "parcelas.situacao_parcela==LIQUIDADA", "parcelas.situacao_parcela==ABERTA")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Parâmetro filter informado mais de uma vez; informe-o uma única vez "
                        + "(listas vão separadas por vírgula no mesmo parâmetro)");
    }

    @Test
    void filtroEmCampoForaDoFieldsFiltraAntesDaSelecao() {
        client.get().uri(ROTA + "?id_operacao={id}&expand=parcelas&fields={fields}&filter={filter}", OPERACAO,
                        "parcelas.numero_parcela_amortizacao", "parcelas.situacao_parcela==LIQUIDADA")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas[*].numero_parcela_amortizacao").isEqualTo(List.of(1, 2, 3, 4, 5))
                .jsonPath("$.data[0].parcelas[0].situacao_parcela").doesNotExist();
    }

    @Test
    void filtroVazioNaoFiltra() {
        consultar(OPERACAO, "parcelas", "")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(12);
    }

    static Stream<Arguments> filtrosInvalidos() {
        return Stream.of(
                Arguments.of("parcelas.numero_parcela_amortizacao==", "RSQL"),
                Arguments.of("parcelas.numero_parcela_amortizacao=xx=4", "RSQL"),
                Arguments.of("(parcelas.numero_parcela_amortizacao==1", "RSQL"),
                Arguments.of("parcelas.inexistente==1", "parcelas.inexistente"),
                Arguments.of("garantias.numero==1", "garantias"),
                Arguments.of("numero_parcela_amortizacao==1", "<bloco>.<campo>"),
                Arguments.of("componentes.numero_componente_financeiro==1", "expand"),
                Arguments.of("parcelas.numero_parcela_amortizacao==quatro", "quatro"),
                Arguments.of("parcelas.data_vencimento_parcela=gt=21/11/2026", "yyyy-MM-dd"),
                Arguments.of("amortizacoes.data_valor_movimento=gt=2026-10-21", "yyyy-MM-ddTHH:mm:ss"),
                Arguments.of("amortizacoes.indicador_ocorrencia_valida==sim", "true ou false"),
                Arguments.of("parcelas.situacao_parcela=gt=ABERTA", "=in="),
                Arguments.of("plano.dados_plano_amortizacao==1", "lista"),
                Arguments.of("saldo.saldo_devedor_operacao=gt=0", "operacao e originacao"),
                Arguments.of("parcelas.numero_parcela_amortizacao==1,saldo.saldo_parcelas.saldo_devedor==0",
                        "mesma lista"));
    }

    @ParameterizedTest(name = "filter={0} -> 400")
    @MethodSource("filtrosInvalidos")
    void filtroInvalidoDevolve400ComProblemDetails(String filter, String trechoDoDetalhe) {
        consultar(OPERACAO, "parcelas,amortizacoes,plano,saldo", filter)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains(trechoDoDetalhe));
    }

    @Test
    void filtroInvalidoEmOperacaoInexistenteDevolve400AntesDeConsultar() {
        consultar("999999999", "parcelas", "parcelas.inexistente==1")
                .expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec consultar(String idOperacao, String expand, String filter) {
        return client.get().uri(ROTA + "?id_operacao={id}&expand={expand}&filter={filter}", idOperacao, expand, filter)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
