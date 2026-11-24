package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Logs observados na saída do processo: JSON (ECS), um log de acesso por requisição com o contexto
 * da requisição e dados pessoais (CPF/CNPJ e conta) sempre mascarados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
@ExtendWith(OutputCaptureExtension.class)
class LogDeAcessoIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String CPF = "52998224725";
    private static final String CONTA = "434115000065874";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;

    @Autowired
    LogDeAcessoIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void cadaRequisicaoGeraUmLogDeAcessoEmJsonComOContexto(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&expand=parcelas,saldo")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", "sistema-custodia")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("consumidor").asString()).isEqualTo("sistema-custodia");
        assertThat(acesso.path("tipo_consulta").asString()).isEqualTo("OPERACAO");
        assertThat(acesso.path("rota").asString()).isEqualTo("fonte_primaria");
        assertThat(acesso.path("uri").asString()).isEqualTo(ROTA);
        assertThat(acesso.path("blocos").valueStream().map(JsonNode::asString).toList())
                .containsExactly("operacao", "originacao", "parcelas", "saldo");
        assertThat(acesso.path("status").asInt()).isEqualTo(200);
        assertThat(acesso.path("duracao_ms").isNumber()).isTrue();
        assertThat(acesso.path("duracao_ms").asLong()).isNotNegative();
        assertThat(acesso.path("id_operacao").asString()).isEqualTo("100000001");
        // Sem cache de blocos neste serviço: contadores presentes (mesmo formato da visão consolidada) e vazios
        assertThat(acesso.path("cache_hits").isObject()).isTrue();
        assertThat(acesso.path("cache_hits").properties()).isEmpty();
        assertThat(acesso.path("cache_misses").isObject()).isTrue();
        assertThat(acesso.path("cache_misses").properties()).isEmpty();
    }

    @Test
    void consumidorForaDoFormatoApareceComoInvalidoNoLog(CapturedOutput saida) {
        var correlationId = novoCorrelationId();
        var consumidor = "consumidor com espaco " + UUID.randomUUID();

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", consumidor)
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        assertThat(logDeAcesso(saida, correlationId).path("consumidor").asString()).isEqualTo("invalido");
        assertThat(saida.getAll()).doesNotContain(consumidor);
    }

    @Test
    void parametroRepetidoE400ComLogDeAcesso(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&id_operacao=100000002")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, detalhe -> assertThat(detalhe)
                        .startsWith("Parâmetro id_operacao informado mais de uma vez"));

        assertThat(logDeAcesso(saida, correlationId).path("status").asInt()).isEqualTo(400);
    }

    @Test
    void consumidorAusenteApareceComoDesconhecido(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=999999999")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isNotFound();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("consumidor").asString()).isEqualTo("desconhecido");
        assertThat(acesso.path("status").asInt()).isEqualTo(404);
        assertThat(acesso.path("blocos").valueStream().map(JsonNode::asString).toList())
                .containsExactly("operacao", "originacao");
    }

    @Test
    void requisicaoRejeitadaPorCorrelationIdInvalidoTambemGeraLogDeAcesso(CapturedOutput saida) {
        var resposta = client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", "invalido_com_underscore")
                .exchange()
                .expectStatus().isBadRequest()
                .returnResult(String.class);
        var correlationIdGerado = resposta.getResponseHeaders().getFirst("x-correlationid");

        var acesso = logDeAcesso(saida, correlationIdGerado);
        assertThat(acesso.path("status").asInt()).isEqualTo(400);
        assertThat(acesso.path("tipo_consulta").asString()).isEqualTo("OPERACAO");
    }

    @Test
    void tipoDeConsultaInvalidoNaoVaiParaOLogEmClaro(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "QUALQUER-COISA")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isBadRequest();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("tipo_consulta").asString()).isEqualTo("invalido");
    }

    @Test
    void cpfCnpjApareceMascaradoNoLogDeAcesso(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("tipo_consulta").asString()).isEqualTo("CLIENTE");
        assertThat(acesso.path("cpf_cnpj").asString()).isEqualTo("529******25");
        assertThat(saida.getAll()).doesNotContain(CPF);
    }

    @Test
    void cnpjAlfanumericoApareceMascaradoNoLogDeAcesso(CapturedOutput saida) {
        var correlationId = novoCorrelationId();
        var cnpj = "12ABC34501DE35";

        client.get().uri(ROTA + "?cpf_cnpj=" + cnpj)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("cpf_cnpj").asString()).isEqualTo("12A*********35");
        assertThat(saida.getAll()).doesNotContain(cnpj);
    }

    @Test
    void contaApareceMascaradaNoLogDeAcesso(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?conta=" + CONTA)
                .header("x-tipo-consulta", "CONTA")
                .header("x-correlationid", correlationId)
                .exchange();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("conta").asString()).isEqualTo("***********5874");
        assertThat(saida.getAll()).doesNotContain(CONTA);
    }

    @Test
    void dadosPessoaisDentroDoFiltroAparecemMascarados(CapturedOutput saida) {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&filter={filtro}",
                        "operacao.cpf_cnpj==" + CPF + ",originacao.conta==" + CONTA)
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange();

        var acesso = logDeAcesso(saida, correlationId);
        assertThat(acesso.path("filter").asString())
                .isEqualTo("operacao.cpf_cnpj==529******25,originacao.conta==***********5874");
        assertThat(saida.getAll()).doesNotContain(CPF).doesNotContain(CONTA);
    }

    private static String novoCorrelationId() {
        return "teste-" + UUID.randomUUID();
    }

    /** Espera o log de acesso da requisição: o appender é assíncrono. */
    private static JsonNode logDeAcesso(CapturedOutput saida, String correlationId) {
        return await().atMost(Duration.ofSeconds(5))
                .until(() -> logsJson(saida).stream()
                        .filter(log -> "acesso".equals(log.path("log").path("logger").asString(null))
                                || "acesso".equals(log.path("log.logger").asString(null)))
                        .filter(log -> correlationId.equals(log.path("correlation_id").asString(null)))
                        .findFirst(), Optional::isPresent)
                .orElseThrow();
    }

    private static List<JsonNode> logsJson(CapturedOutput saida) {
        return saida.getOut().lines()
                .filter(linha -> linha.startsWith("{"))
                .map(JSON::readTree)
                .toList();
    }
}
