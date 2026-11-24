package br.com.consultas.operacoescredito;

import br.com.consultas.operacoescredito.domain.Bloco;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O catálogo deste serviço é o do contrato (ADR-0017): o {@code catalogo-campos.yml} local tem o mesmo conteúdo do
 * de {@code consultas-operacoes-credito} ({@link ContratoOpenApi#CATALOGO}), e cada bloco dele tem no
 * {@code openapi.yaml} ({@link ContratoOpenApi#OPENAPI}) o schema {@code bloco.<nome>} com a mesma estrutura e
 * exatamente os mesmos campos, com o tipo e o {@code anulavel} do catálogo, também nos campos compostos. Um campo
 * novo no contrato que este serviço ainda não conhece falha aqui.
 */
class ContratoOpenApiCatalogoTest {

    private static final String PREFIXO_REF = "#/components/schemas/";
    private static final Path CATALOGO_LOCAL = Path.of("src/main/resources/catalogo-campos.yml");
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final JsonNode CONTRATO = YAML.readTree(ContratoOpenApi.OPENAPI.toFile());
    private static final JsonNode CATALOGO = YAML.readTree(CATALOGO_LOCAL.toFile()).at("/consultas/catalogo/blocos");
    private static final JsonNode SCHEMAS = CONTRATO.at("/components/schemas");

    @Test
    void catalogoLocalTemOMesmoConteudoDoCatalogoDoContrato() {
        // Comparação do conteúdo YAML, não dos bytes: comentários e fim de linha não são contrato
        var divergencias = new ArrayList<String>();
        diferencas(divergencias, "", YAML.readTree(CATALOGO_LOCAL.toFile()),
                YAML.readTree(ContratoOpenApi.CATALOGO.toFile()));

        assertThat(divergencias).as("%s x %s (local x contrato)", CATALOGO_LOCAL, ContratoOpenApi.CATALOGO)
                .isEmpty();
    }

    @Test
    void diferencaEntreCatalogosEApontadaPeloCaminho() {
        var local = YAML.readTree("""
                consultas:
                  catalogo:
                    blocos:
                      - bloco: operacao
                        campos: [{nome: a, tipo: texto}, {nome: b, tipo: numero}]
                """);
        var contrato = YAML.readTree("""
                consultas:
                  catalogo:
                    blocos:
                      - bloco: operacao
                        campos: [{nome: a, tipo: data}, {nome: b, tipo: numero, anulavel: true}]
                """);
        var divergencias = new ArrayList<String>();

        diferencas(divergencias, "", local, contrato);

        assertThat(divergencias).containsExactly(
                "/consultas/catalogo/blocos/0/campos/0/tipo: local \"texto\", contrato \"data\"",
                "/consultas/catalogo/blocos/0/campos/1/anulavel: local ausente, contrato true");
        divergencias.clear();
        diferencas(divergencias, "", local, local);
        assertThat(divergencias).isEmpty();
    }

    @Test
    void cadaBlocoDoCatalogoTemSchemaComOsMesmosCamposTiposENulidade() {
        var divergencias = new ArrayList<String>();
        for (var bloco : CATALOGO) {
            var nome = bloco.get("bloco").asString();
            var schema = SCHEMAS.get("bloco." + nome);
            if (schema == null) {
                divergencias.add("bloco " + nome + ": sem schema bloco." + nome);
                continue;
            }
            var lista = bloco.get("estrutura").asString().equals("lista");
            assertTipo(divergencias, nome, schema, lista ? "array" : "object");
            // null = bloco indisponível; operacao nunca é indisponível (sem ele a operação não existe)
            assertNulo(divergencias, nome, schema, !nome.equals(Bloco.OPERACAO.nome()));
            compararCampos(divergencias, nome, bloco.get("campos"), lista ? schema.get("items") : schema);
        }
        assertThat(divergencias).as("%s x %s", CATALOGO_LOCAL, ContratoOpenApi.OPENAPI).isEmpty();
    }

    @Test
    void operacaoDoContratoTemUmaChavePorBlocoEOsSchemasSaoOsDoCatalogo() {
        var blocos = Arrays.stream(Bloco.values()).map(Bloco::nome).toList();
        assertThat(nomes(SCHEMAS.at("/Operacao/properties"))).containsExactlyElementsOf(blocos);
        assertThat(nomes(SCHEMAS).stream().filter(nome -> nome.startsWith("bloco.")).toList())
                .containsExactlyElementsOf(blocos.stream().map(nome -> "bloco." + nome).toList());
        var doCatalogo = new ArrayList<String>();
        CATALOGO.forEach(bloco -> doCatalogo.add(bloco.get("bloco").asString()));
        assertThat(doCatalogo).containsExactlyElementsOf(blocos);
    }

    @Test
    void headerDeBlocosIndisponiveisAceitaExatamenteOsNomesDosBlocos() {
        var padrao = CONTRATO.at("/components/headers/BlocosIndisponiveis/schema/pattern").asString();
        var nomes = String.join("|", Arrays.stream(Bloco.values()).map(Bloco::nome).toList());
        assertThat(padrao).isEqualTo("^(" + nomes + ")(,(" + nomes + "))*$");
    }

    /** Caminhos (JSON Pointer) em que {@code local} e {@code contrato} diferem, com os dois valores. */
    private static void diferencas(List<String> divergencias, String caminho, JsonNode local, JsonNode contrato) {
        if (local == null || contrato == null || local.isObject() != contrato.isObject()
                || local.isArray() != contrato.isArray()) {
            if (local != contrato && (local == null || !local.equals(contrato))) {
                divergencias.add(caminho + ": local " + valor(local) + ", contrato " + valor(contrato));
            }
            return;
        }
        if (local.isObject()) {
            var chaves = new LinkedHashSet<>(nomes(local));
            chaves.addAll(nomes(contrato));
            chaves.forEach(chave -> diferencas(divergencias, caminho + "/" + chave, local.get(chave),
                    contrato.get(chave)));
        } else if (local.isArray()) {
            for (int i = 0; i < Math.max(local.size(), contrato.size()); i++) {
                diferencas(divergencias, caminho + "/" + i, local.get(i), contrato.get(i));
            }
        } else if (!local.equals(contrato)) {
            divergencias.add(caminho + ": local " + valor(local) + ", contrato " + valor(contrato));
        }
    }

    private static String valor(JsonNode no) {
        return no == null ? "ausente" : no.toString();
    }

    private static void compararCampos(List<String> divergencias, String caminho, JsonNode campos, JsonNode objeto) {
        var propriedades = objeto == null ? null : objeto.get("properties");
        if (propriedades == null) {
            divergencias.add(caminho + ": schema sem properties");
            return;
        }
        var doCatalogo = new TreeSet<String>();
        for (var campo : campos) {
            var nome = campo.get("nome").asString();
            doCatalogo.add(nome);
            var propriedade = propriedades.get(nome);
            if (propriedade == null) {
                divergencias.add(caminho + "." + nome + ": ausente no contrato");
                continue;
            }
            compararCampo(divergencias, caminho + "." + nome, campo, resolver(propriedade));
        }
        var noContrato = new TreeSet<>(nomes(propriedades));
        noContrato.removeAll(doCatalogo);
        noContrato.forEach(nome -> divergencias.add(caminho + "." + nome + ": no contrato e fora do catálogo"));
    }

    private static void compararCampo(List<String> divergencias, String caminho, JsonNode campo, JsonNode schema) {
        var tipo = campo.get("tipo").asString();
        assertNulo(divergencias, caminho, schema, campo.path("anulavel").asBoolean(false));
        switch (tipo) {
            case "numero" -> assertTipo(divergencias, caminho, schema, "integer");
            case "decimal" -> assertTipo(divergencias, caminho, schema, "number");
            case "texto" -> assertTipo(divergencias, caminho, schema, "string");
            case "booleano" -> assertTipo(divergencias, caminho, schema, "boolean");
            case "data" -> assertFormato(divergencias, caminho, schema, "^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
            case "data_hora" -> assertFormato(divergencias, caminho, schema,
                    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$");
            case "objeto" -> {
                assertTipo(divergencias, caminho, schema, "object");
                compararCampos(divergencias, caminho, campo.get("campos"), schema);
            }
            case "lista" -> {
                assertTipo(divergencias, caminho, schema, "array");
                compararCampos(divergencias, caminho, campo.get("campos"), schema.get("items"));
            }
            default -> divergencias.add(caminho + ": tipo do catálogo desconhecido " + tipo);
        }
    }

    private static void assertFormato(List<String> divergencias, String caminho, JsonNode schema, String padrao) {
        assertTipo(divergencias, caminho, schema, "string");
        if (!padrao.equals(schema.path("pattern").asString(null))) {
            divergencias.add(caminho + ": pattern " + schema.path("pattern").asString(null) + ", esperado " + padrao);
        }
    }

    private static void assertTipo(List<String> divergencias, String caminho, JsonNode schema, String tipo) {
        var noContrato = schema.path("type").asString(null);
        if (!tipo.equals(noContrato)) {
            divergencias.add(caminho + ": type " + noContrato + ", esperado " + tipo);
        }
    }

    private static void assertNulo(List<String> divergencias, String caminho, JsonNode schema, boolean anulavel) {
        if (schema.path("nullable").asBoolean(false) != anulavel) {
            divergencias.add(caminho + ": nullable deveria ser " + anulavel);
        }
    }

    private static JsonNode resolver(JsonNode schema) {
        var ref = schema.path("$ref").asString(null);
        return ref == null ? schema : SCHEMAS.get(ref.substring(PREFIXO_REF.length()));
    }

    private static Set<String> nomes(JsonNode objeto) {
        var nomes = new LinkedHashSet<String>();
        objeto.properties().forEach(propriedade -> nomes.add(propriedade.getKey()));
        return nomes;
    }
}
