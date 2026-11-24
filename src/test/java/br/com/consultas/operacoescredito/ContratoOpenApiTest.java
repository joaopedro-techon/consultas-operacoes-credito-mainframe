package br.com.consultas.operacoescredito;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O validador de {@link ContratoOpenApi} aceita o que o contrato descreve e recusa o que diverge dele, sem subir a
 * aplicação: protege o teste de conformidade de virar um teste que nunca falha.
 */
class ContratoOpenApiTest {

    private static final String FONTE_PRIMARIA = "/v1/operacoes-credito/fonte-primaria";
    private static final OpenApiInteractionValidator VALIDADOR = ContratoOpenApi.validador(ContratoOpenApi.OPENAPI);
    private static final String PROBLEMA_404 = """
            {"type":"about:blank","title":"Operação de crédito não encontrada","status":404,
             "detail":"Operação 999 não encontrada","instance":"/v1/operacoes-credito/fonte-primaria",
             "correlation_id":"abc-123"}""";

    @Test
    void semAPropriedadeOContratoEACopiaVersionada() {
        assertThat(ContratoOpenApi.arquivo(null, "openapi.yaml"))
                .isEqualTo(Path.of(ContratoOpenApi.DIRETORIO_PADRAO, "openapi.yaml"));
        assertThat(ContratoOpenApi.arquivo(" ", "catalogo-campos.yml"))
                .isEqualTo(Path.of(ContratoOpenApi.DIRETORIO_PADRAO, "catalogo-campos.yml"));
    }

    @Test
    void comAPropriedadeOContratoEODoDiretorioIndicado(@TempDir Path diretorio) throws IOException {
        var baixado = Files.writeString(diretorio.resolve("openapi.yaml"), "openapi: 3.0.3");

        assertThat(ContratoOpenApi.arquivo(diretorio.toString(), "openapi.yaml")).isEqualTo(baixado);
    }

    @Test
    void contratoAusenteNoDiretorioIndicadoFalha(@TempDir Path diretorio) {
        assertThatThrownBy(() -> ContratoOpenApi.arquivo(diretorio.toString(), "openapi.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openapi.yaml")
                .hasMessageContaining(ContratoOpenApi.PROPRIEDADE_DIRETORIO);
    }

    @Test
    void exemploDaDocumentacaoEstaConforme() throws IOException {
        var exemplo = Files.readAllBytes(Path.of("docs/exemplo_operacao.json"));

        assertThat(validar(200, json(), exemplo).getMessages()).isEmpty();
    }

    @Test
    void blocoIndisponivelNuloComOHeaderEstaConforme() {
        var headers = json();
        headers.add("x-blocos-indisponiveis", "plano,saldo");
        var corpo = """
                {"data":[{"operacao":{"id_operacao":1,"custodia":"F5","data_baixa_operacao":null},
                          "originacao":{"id_operacao":1},"plano":null,"saldo":null,"recebimentos":[]}]}""";

        assertThat(validar(200, headers, corpo).getMessages()).isEmpty();
    }

    @Test
    void blocoComCamposSelecionadosOuSemCampoDaCustodiaEstaConforme() {
        // fields reduz os blocos e a F5 não tem dependencia_contabil: nenhum campo de bloco é obrigatório
        var corpo = """
                {"data":[{"operacao":{"id_operacao":1},"originacao":{"canal_contratacao":"MOBILE"},
                          "saldo":{"saldo_parcelas":[{"saldo_devedor":1.5}]}}]}""";

        assertThat(validar(200, json(), corpo).getMessages()).isEmpty();
    }

    @Test
    void campoForaDoCatalogoNoBlocoNaoDiverge() {
        // Os blocos são Map (ADR-0005): o que a fonte trouxer além do catálogo passa adiante sem ser recusado
        var corpo = "{\"data\":[{\"operacao\":{\"id_operacao\":1,\"campo_novo\":\"x\"},\"originacao\":{}}]}";

        assertThat(validar(200, json(), corpo).getMessages()).isEmpty();
    }

    @Test
    void consultaSemOperacoesEstaConforme() {
        assertThat(validar(200, json(), "{\"data\":[]}").getMessages()).isEmpty();
    }

    @Test
    void problemaComOStatusDaRespostaEstaConforme() {
        assertThat(validar(404, problema(), PROBLEMA_404).getMessages()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"data\":[{\"operacao\":{\"id_operacao\":\"1\"},\"originacao\":{}}]}",
            "{\"data\":[{\"operacao\":{\"id_operacao\":1,\"data_contratacao_operacao\":\"21/11/2026\"},\"originacao\":{}}]}",
            "{\"data\":[{\"operacao\":{\"situacao_operacao_credito\":null},\"originacao\":{}}]}",
            "{\"data\":[{\"operacao\":{\"custodia\":\"XX\"},\"originacao\":{}}]}",
            "{\"data\":[{\"operacao\":null,\"originacao\":{}}]}",
            "{\"data\":[{\"operacao\":{}}]}",
            "{\"data\":[{\"operacao\":{},\"originacao\":{},\"garantias\":[]}]}",
            "{\"data\":[{\"operacao\":{},\"originacao\":{},\"parcelas\":{}}]}",
            "{\"data\":[{\"operacao\":{},\"originacao\":{},\"saldo\":{\"saldo_devedor_operacao\":\"10.00\"}}]}",
            "{\"data\":[{\"operacao\":{},\"originacao\":{},"
                    + "\"amortizacoes\":[{\"data_valor_movimento\":\"2026-11-21\"}]}]}",
            "{\"data\":{}}",
            "{\"operacoes\":[]}"})
    void corpoForaDoSchemaDiverge(String corpo) {
        assertThat(validar(200, json(), corpo).hasErrors()).isTrue();
    }

    @Test
    void respostaSemCorrelationIdDiverge() {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");

        assertThat(validar(200, headers, "{\"data\":[]}").hasErrors()).isTrue();
    }

    @Test
    void headerDeBlocosIndisponiveisComBlocoDesconhecidoDiverge() {
        var headers = json();
        headers.add("x-blocos-indisponiveis", "plano,garantias");

        assertThat(validar(200, headers, "{\"data\":[]}").hasErrors()).isTrue();
    }

    @Test
    void erroSemProblemDetailsDiverge() {
        assertThat(validar(404, json(), PROBLEMA_404).hasErrors()).isTrue();
    }

    @Test
    void problemaComStatusDiferenteDoHttpDiverge() {
        assertThat(validar(502, problema(), PROBLEMA_404).hasErrors()).isTrue();
    }

    @Test
    void problemaSemCorrelationIdNoCorpoDiverge() {
        var corpo = "{\"title\":\"Erro interno\",\"status\":500,\"detail\":\"Erro inesperado\"}";

        assertThat(validar(500, problema(), corpo).hasErrors()).isTrue();
    }

    @ParameterizedTest(name = "{0} sem Retry-After")
    @ValueSource(ints = {429, 503})
    void indisponibilidadeSemRetryAfterDiverge(int status) {
        var corpo = "{\"title\":\"t\",\"status\":" + status + ",\"detail\":\"d\",\"correlation_id\":\"abc\"}";

        assertThat(validar(status, problema(), corpo).hasErrors()).isTrue();
        var comRetryAfter = problema();
        comRetryAfter.add(HttpHeaders.RETRY_AFTER, "1");
        assertThat(validar(status, comRetryAfter, corpo).getMessages()).isEmpty();
    }

    @Test
    void statusForaDoContratoDiverge() {
        var corpo = "{\"title\":\"t\",\"status\":405,\"detail\":\"d\",\"correlation_id\":\"abc\"}";

        assertThat(validar(405, problema(), corpo).hasErrors()).isTrue();
    }

    private static ValidationReport validar(int status, HttpHeaders headers, String corpo) {
        return validar(status, headers, corpo.getBytes(StandardCharsets.UTF_8));
    }

    private static ValidationReport validar(int status, HttpHeaders headers, byte[] corpo) {
        return ContratoOpenApi.validar(VALIDADOR, FONTE_PRIMARIA, status, headers, corpo);
    }

    private static HttpHeaders json() {
        return headers("application/json");
    }

    private static HttpHeaders problema() {
        return headers("application/problem+json");
    }

    private static HttpHeaders headers(String contentType) {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, contentType);
        headers.add("x-correlationid", "abc-123");
        return headers;
    }
}
