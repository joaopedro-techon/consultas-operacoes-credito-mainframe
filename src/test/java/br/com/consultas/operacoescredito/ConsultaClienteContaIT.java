package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta das consultas CLIENTE e CONTA na fonte primária.
 * Valores esperados vêm da massa do gateway simulado em {@code wiremock/mappings}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ConsultaClienteContaIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";

    private final RestTestClient client;

    @Autowired
    ConsultaClienteContaIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void consultaPorCpfDevolveTodasAsOperacoesDoClienteDaMaisRecenteParaAMaisAntiga() {
        consultar("CLIENTE", "cpf_cnpj=52998224725")
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[*].operacao.data_contratacao_operacao")
                .isEqualTo(List.of("2026-10-28", "2026-05-21", "2025-09-15"))
                .jsonPath("$.data[*].originacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[1].originacao.canal_contratacao").isEqualTo("MOBILE")
                .jsonPath("$.data[1].length()").isEqualTo(2);
    }

    @Test
    void consultaPorCnpjAlfanumericoDevolveAsOperacoesDoCliente() {
        consultar("CLIENTE", "cpf_cnpj=12ABC34501DE35")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000004, 100000005))
                .jsonPath("$.data[*].operacao.cpf_cnpj").isEqualTo(List.of("12ABC34501DE35", "12ABC34501DE35"))
                .jsonPath("$.data[*].originacao.dependencia_contabil").isEqualTo(List.of("031", "031"));
    }

    @Test
    void consultaPorContaDevolveAsOperacoesDaContaDaMaisRecenteParaAMaisAntiga() {
        consultar("CONTA", "conta=434115000065874")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000001, 100000002))
                .jsonPath("$.data[*].originacao.canal_contratacao").isEqualTo(List.of("MOBILE", "AGENCIA"));
    }

    @ParameterizedTest(name = "{0} {1} com limite {2}")
    @CsvSource(delimiter = '|', textBlock = """
            CLIENTE | cpf_cnpj=52998224725  | 1   | 100000003
            CLIENTE | cpf_cnpj=52998224725  | 2   | 100000003,100000001
            CLIENTE | cpf_cnpj=52998224725  | 500 | 100000003,100000001,100000002
            CONTA   | conta=434115000055501 | 3   | 100000114,100000113,100000112
            CONTA   | conta=434115000065874 | 1   | 100000001
            """)
    void limiteOperacoesDevolveSoAsMaisRecentes(String tipoConsulta, String query, int limite, String esperadas) {
        var ids = Arrays.stream(esperadas.split(",")).map(Integer::valueOf).toList();
        consultar(tipoConsulta, query + "&limite_operacoes=" + limite + "&expand=parcelas")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].originacao.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].parcelas[0].id_operacao").isEqualTo(ids);
    }

    @ParameterizedTest(name = "{0} {1} sem operações -> 200 com data vazio")
    @CsvSource(delimiter = '|', textBlock = """
            CLIENTE | cpf_cnpj=70000000159
            CLIENTE | cpf_cnpj=98XYZ76501AB46
            CONTA   | conta=999999999
            """)
    void clienteOuContaSemOperacoesDevolveListaVazia(String tipoConsulta, String query) {
        consultar(tipoConsulta, query)
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody().json("""
                        {"data": []}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void blocoAusenteEmUmaDasOperacoesDoClienteVemNullComHeaderDeIndisponiveis() {
        consultar("CLIENTE", "cpf_cnpj=52998224725&expand=saldo,plano")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo")
                .expectBody()
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(100000003)
                .jsonPath("$.data[0].saldo").isEmpty()
                .jsonPath("$.data[0].plano").isEmpty()
                .jsonPath("$.data[1].saldo.saldo_devedor_operacao").isEqualTo(3956.00)
                .jsonPath("$.data[2].plano.id_operacao").isEqualTo(100000002);
    }

    @Test
    void fieldsSelecionaCamposEmTodasAsOperacoesDoCliente() {
        consultar("CONTA", "conta=434115000077712&expand=saldo"
                + "&fields=operacao.data_contratacao_operacao,saldo.saldo_devedor_operacao")
                .expectStatus().isOk()
                .expectBody().json("""
                        {"data": [
                          {"operacao": {"id_operacao": 100000004, "data_contratacao_operacao": "2026-08-05"},
                           "originacao": {"id_operacao": 100000004, "canal_contratacao": "AGENCIA",
                                          "dependencia_contabil": "031"},
                           "saldo": {"id_operacao": 100000004, "saldo_devedor_operacao": 39200.00}},
                          {"operacao": {"id_operacao": 100000005, "data_contratacao_operacao": "2026-02-10"},
                           "originacao": {"id_operacao": 100000005, "canal_contratacao": "INTERNET",
                                          "dependencia_contabil": "031"},
                           "saldo": {"id_operacao": 100000005, "saldo_devedor_operacao": 0.00}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void clienteComMaisDeCemChavesDeBlocosDevolveTodosOsBlocosDeTodasAsOperacoes() {
        var ids = IntStream.iterate(100000114, id -> id >= 100000101, id -> id - 1).boxed().toList();
        consultar("CLIENTE", "cpf_cnpj=11222333000181"
                + "&expand=componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].originacao.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].componentes[0].id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].parcelas[1].id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].plano.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].saldo.id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].controles_iof[0].id_operacao").isEqualTo(ids)
                .jsonPath("$.data[*].amortizacoes").isEqualTo(semItens(ids.size()))
                .jsonPath("$.data[*].recebimentos").isEqualTo(semItens(ids.size()));
    }

    @ParameterizedTest(name = "CLIENTE com {0} -> 400")
    @ValueSource(strings = {
            "52998224726",      // CPF com DV errado
            "52998224735",      // CPF com primeiro DV errado
            "12ABC34501DE36",   // CNPJ alfanumérico com DV errado
            "11222333000182",   // CNPJ numérico com DV errado
            "12abc34501de35",   // CNPJ alfanumérico em minúsculas
            "11111111111",      // CPF com dígitos repetidos
            "5299822472",       // 10 dígitos
            "529982247250",     // 12 dígitos
            "529.982.247-25",   // CPF formatado
            "12ABC34501DEA5",   // DV não numérico
    })
    void cpfCnpjInvalidoDevolve400(String cpfCnpj) {
        consultar("CLIENTE", "cpf_cnpj=" + cpfCnpj)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("cpf_cnpj"));
    }

    @ParameterizedTest(name = "CONTA com {0} -> 400")
    @ValueSource(strings = {"4341150000658A4", "434115-000065874", "434115 000065874", "-1"})
    void contaComCaractereNaoNumericoDevolve400(String conta) {
        client.get().uri(uri -> uri.path(ROTA).queryParam("conta", conta).build())
                .header("x-tipo-consulta", "CONTA")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("conta"));
    }

    @ParameterizedTest(name = "conta com {0} dígitos -> 400 sem ir ao mainframe")
    @ValueSource(ints = {21, 3000})
    void contaLongaDemaisDevolve400(int digitos) {
        consultar("CONTA", "conta=" + "1".repeat(digitos))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Parâmetro conta deve ter só dígitos, de 1 a 20 dígitos");
    }

    @ParameterizedTest(name = "{0} com limite_operacoes={2} -> 400")
    @CsvSource(delimiter = '|', textBlock = """
            CLIENTE | cpf_cnpj=52998224725  | 0
            CLIENTE | cpf_cnpj=52998224725  | 501
            CLIENTE | cpf_cnpj=52998224725  | -1
            CLIENTE | cpf_cnpj=52998224725  | abc
            CLIENTE | cpf_cnpj=52998224725  | 1.5
            CLIENTE | cpf_cnpj=52998224725  | 99999999999
            CLIENTE | cpf_cnpj=52998224725  | ''
            CONTA   | conta=434115000065874 | 0
            CONTA   | conta=434115000065874 | 501
            """)
    void limiteOperacoesForaDaFaixaDevolve400(String tipoConsulta, String query, String limite) {
        consultar(tipoConsulta, query + "&limite_operacoes=" + limite)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("limite_operacoes", "1", "500"));
    }

    private static List<List<Object>> semItens(int operacoes) {
        return Stream.generate(List::<Object>of).limit(operacoes).toList();
    }

    private RestTestClient.ResponseSpec consultar(String tipoConsulta, String query) {
        return client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipoConsulta)
                .exchange();
    }
}
