package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Métricas observadas pelo Actuator ({@code /actuator/metrics}) depois de consultas reais:
 * tags de baixa cardinalidade, URI como template e nenhum dado pessoal ou identificador em tag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class MetricasIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;

    @Autowired
    MetricasIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void requisicaoHttpTemTagsDeConsumidorTipoDeConsultaRotaEStatusComUriTemplate() {
        var consumidor = novoConsumidor();

        consultarOperacao(consumidor, "100000001", null);

        var metrica = metrica("http.server.requests", "consumidor:" + consumidor, "tipo_consulta:OPERACAO",
                "rota:fonte_primaria", "status:200", "uri:" + ROTA);
        assertThat(contagem(metrica)).isEqualTo(1.0);
    }

    @Test
    void contaCadaBlocoSolicitadoPorBlocoEConsumidor() {
        var consumidor = novoConsumidor();

        consultarOperacao(consumidor, "100000001", "parcelas,saldo");
        consultarOperacao(consumidor, "100000002", "saldo");

        assertThat(contagemDeBlocos(consumidor, "operacao")).isEqualTo(2.0);
        assertThat(contagemDeBlocos(consumidor, "originacao")).isEqualTo(2.0);
        assertThat(contagemDeBlocos(consumidor, "parcelas")).isEqualTo(1.0);
        assertThat(contagemDeBlocos(consumidor, "saldo")).isEqualTo(2.0);
        assertThat(contagem(metrica("consultas.blocos", "consumidor:" + consumidor, "bloco:saldo",
                "tipo_consulta:OPERACAO", "rota:fonte_primaria", "status:200"))).isEqualTo(2.0);
    }

    @Test
    void blocoForaDoCatalogoNaoViraTag() {
        var consumidor = novoConsumidor();

        consultarOperacao(consumidor, "100000001", "xpto");

        assertThat(valoresDaTag("consultas.blocos", "bloco", "consumidor:" + consumidor))
                .containsExactlyInAnyOrder("operacao", "originacao");
    }

    @Test
    void nenhumaTagTemIdentificadorDaOperacaoCpfOuConta() {
        var consumidor = novoConsumidor();

        consultarOperacao(consumidor, "100000001", null);
        client.get().uri(ROTA + "?cpf_cnpj=52998224725")
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-consumidor", consumidor)
                .exchange();
        client.get().uri(ROTA + "?conta=434115000065874")
                .header("x-tipo-consulta", "CONTA")
                .header("x-consumidor", consumidor)
                .exchange();

        for (var nome : new String[] {"http.server.requests", "consultas.blocos"}) {
            var tags = metrica(nome, "consumidor:" + consumidor).path("availableTags").toString();
            assertThat(tags).doesNotContain("100000001", "52998224725", "434115000065874",
                    "id_operacao", "cpf_cnpj", "\"conta\"");
        }
    }

    @Test
    void consumidorForaDoFormatoViraInvalidoNaTag() {
        var consumidor = "consumidor com espaco " + UUID.randomUUID();

        consultarOperacao(consumidor, "100000001", null);

        assertThat(valoresDaTag("http.server.requests", "consumidor", "uri:" + ROTA)).contains("invalido")
                .doesNotContain(consumidor);
    }

    @Test
    void rotaInexistenteSobOPrefixoDaConsultaNaoContaBlocos() {
        var consumidor = novoConsumidor();

        client.get().uri(ROTA + "/xyz?id_operacao=100000001&expand=parcelas")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", consumidor)
                .exchange()
                .expectStatus().isNotFound();

        assertThat(contagemOuZero("consultas.blocos", "consumidor:" + consumidor)).isZero();
        assertThat(contagem(metrica("http.server.requests", "consumidor:" + consumidor, "status:404")))
                .isEqualTo(1.0);
    }

    @Test
    void requisicaoRejeitadaPeloContextoTambemEMedida() {
        var consumidor = novoConsumidor();

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", consumidor)
                .header("x-correlationid", "invalido!")
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(contagem(metrica("http.server.requests", "consumidor:" + consumidor, "status:400",
                "tipo_consulta:OPERACAO"))).isEqualTo(1.0);
    }

    @Test
    void chamadaAoMainframeEMedidaComoDependencia() {
        var consumidor = novoConsumidor();
        var antes = contagemOuZero("consultas.dependencia", "dependencia:fonte_primaria", "operacao:operacao");

        consultarOperacao(consumidor, "100000001", null);

        assertThat(contagemOuZero("consultas.dependencia", "dependencia:fonte_primaria", "operacao:operacao"))
                .isGreaterThan(antes);
    }

    private double contagemDeBlocos(String consumidor, String bloco) {
        return contagem(metrica("consultas.blocos", "consumidor:" + consumidor, "bloco:" + bloco));
    }

    private List<String> valoresDaTag(String nome, String tag, String... filtros) {
        for (var disponivel : metrica(nome, filtros).path("availableTags")) {
            if (tag.equals(disponivel.path("tag").asString())) {
                return disponivel.path("values").valueStream().map(JsonNode::asString).toList();
            }
        }
        return List.of();
    }

    private double contagemOuZero(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome).append('?');
        for (var tag : tags) {
            uri.append("tag=").append(tag).append('&');
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return 0.0;
        }
        return contagem(JSON.readTree(resultado.getResponseBody()));
    }

    private void consultarOperacao(String consumidor, String idOperacao, String expand) {
        var uri = ROTA + "?id_operacao=" + idOperacao + (expand == null ? "" : "&expand=" + expand);
        client.get().uri(uri)
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", consumidor)
                .exchange();
    }

    private JsonNode metrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome).append('?');
        for (var tag : tags) {
            uri.append("tag=").append(tag).append('&');
        }
        var corpo = client.get().uri(uri.toString())
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
        return JSON.readTree(corpo);
    }

    private static double contagem(JsonNode metrica) {
        for (var medida : metrica.path("measurements")) {
            if ("COUNT".equals(medida.path("statistic").asString())) {
                return medida.path("value").asDouble();
            }
        }
        throw new AssertionError("Métrica sem COUNT: " + metrica);
    }

    private static String novoConsumidor() {
        return "consumidor-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
