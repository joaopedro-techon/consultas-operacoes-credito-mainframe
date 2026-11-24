package br.com.consultas.operacoescredito.config.observabilidade;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O dashboard do Grafana mostra a rota da API: o filtro de {@code uri} das métricas HTTP precisa pegar o template
 * {@code /v1/operacoes-credito/fonte-primaria}, e o de antes da separação dos repositórios
 * ({@code /v1/operacoes-credito/{rota}}, ADR-0017), para o histórico continuar no painel.
 */
class DashboardGrafanaTest {

    private static final Path DASHBOARD =
            Path.of("observabilidade/grafana/dashboards/consultas-operacoes-credito.json");
    private static final Pattern FILTRO_URI = Pattern.compile("uri(=~?)\\\\\"([^\\\\]*)\\\\\"");

    @Test
    void filtrosDeUriCobremARotaDaFontePrimaria() throws IOException {
        var dashboard = Files.readString(DASHBOARD);
        var filtros = FILTRO_URI.matcher(dashboard).results().toList();

        assertThat(filtros).isNotEmpty().allSatisfy(filtro -> {
            assertThat(filtro.group(1)).as("filtro por regex, não exato: %s", filtro.group()).isEqualTo("=~");
            var regex = Pattern.compile(filtro.group(2));
            assertThat(regex.matcher("/v1/operacoes-credito/fonte-primaria").matches()).isTrue();
            assertThat(regex.matcher("/v1/operacoes-credito/{rota}").matches()).isTrue();
            assertThat(regex.matcher("/actuator/health").matches()).isFalse();
        });
    }
}
