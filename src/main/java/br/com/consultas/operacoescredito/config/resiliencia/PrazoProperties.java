package br.com.consultas.operacoescredito.config.resiliencia;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazo da requisição por tipo de consulta (fail fast → 504), acima das rodadas sequenciais de chamadas ao
 * mainframe (descoberta de custódia, blocos, páginas), com p99 de 500 ms por chamada. As propriedades continuam
 * em {@code consultas.prazo.fonte-primaria.*}, os mesmos nomes de antes da separação dos repositórios (ADR-0017).
 *
 * @param fontePrimaria prazos da rota da fonte primária
 */
@ConfigurationProperties("consultas.prazo")
public record PrazoProperties(@DefaultValue FontePrimaria fontePrimaria) {

    /** Prazo do tipo de consulta. */
    public Duration de(TipoConsulta tipo) {
        return fontePrimaria.de(tipo);
    }

    /**
     * @param operacao prazo de {@code OPERACAO}
     * @param cliente  prazo de {@code CLIENTE}
     * @param conta    prazo de {@code CONTA}
     */
    public record FontePrimaria(
            @DefaultValue("2500ms") Duration operacao,
            @DefaultValue("6s") Duration cliente,
            @DefaultValue("6s") Duration conta) {

        public FontePrimaria {
            positivo(operacao, "fonte-primaria.operacao");
            positivo(cliente, "fonte-primaria.cliente");
            positivo(conta, "fonte-primaria.conta");
        }

        public Duration de(TipoConsulta tipo) {
            return switch (tipo) {
                case OPERACAO -> operacao;
                case CLIENTE -> cliente;
                case CONTA -> conta;
            };
        }
    }

    private static void positivo(Duration prazo, String nome) {
        if (prazo == null || prazo.isNegative() || prazo.isZero()) {
            throw new IllegalArgumentException("consultas.prazo." + nome + " deve ser positivo");
        }
    }
}
