package br.com.consultas.operacoescredito.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * O que uma requisição de consulta consumiu, como chegou à borda HTTP, ainda com CPF/CNPJ e conta em claro.
 * Vira um evento de consumo pseudonimizado pelo {@link RegistrarConsumoUseCase}.
 *
 * @param timestamp                     início da requisição
 * @param correlationId                 {@code x-correlationid} da requisição
 * @param traceId                       trace W3C da requisição; {@code null} sem tracing
 * @param consumidor                    {@code x-consumidor} (id da API key) ou {@code desconhecido}
 * @param rota                          {@code fonte_primaria} (o serviço da visão consolidada usa {@code visao_consolidada})
 * @param tipoConsulta                  {@code OPERACAO}, {@code CLIENTE}, {@code CONTA}, {@code invalido}; {@code null} se ausente
 * @param idOperacao                    {@code id_operacao} informado e numérico; {@code null} caso contrário
 * @param cpfCnpj                       {@code cpf_cnpj} informado (em claro); {@code null} se ausente
 * @param conta                         {@code conta} informada (em claro); {@code null} se ausente
 * @param blocosSolicitados             blocos pedidos (sempre {@code operacao} e {@code originacao}), só nomes válidos
 * @param camposSolicitados             itens do {@code fields} bem formados ({@code bloco.campo})
 * @param filtro                        {@code filter} como veio; {@code null} se ausente
 * @param limiteOperacoes               {@code limite_operacoes} informado e numérico; {@code null} caso contrário
 * @param quantidadeOperacoesRetornadas operações em {@code data} (0 em erro)
 * @param blocosIndisponiveis           blocos de {@code x-blocos-indisponiveis}
 * @param cacheHits                     blocos encontrados no cache, por bloco
 * @param cacheMisses                   blocos procurados e ausentes no cache, por bloco
 * @param statusHttp                    status da resposta
 * @param codigoErro                    código do erro (status ≥ 400); {@code null} em sucesso
 * @param duracaoMs                     duração da requisição em milissegundos
 */
public record Consumo(Instant timestamp, String correlationId, String traceId, String consumidor, String rota,
                      String tipoConsulta, Long idOperacao, String cpfCnpj, String conta,
                      List<String> blocosSolicitados, List<String> camposSolicitados, String filtro,
                      Integer limiteOperacoes, int quantidadeOperacoesRetornadas, List<String> blocosIndisponiveis,
                      Map<String, Long> cacheHits, Map<String, Long> cacheMisses, int statusHttp, String codigoErro,
                      long duracaoMs) {

    public Consumo {
        Objects.requireNonNull(timestamp, "timestamp");
        blocosSolicitados = List.copyOf(blocosSolicitados);
        camposSolicitados = List.copyOf(camposSolicitados);
        blocosIndisponiveis = List.copyOf(blocosIndisponiveis);
        cacheHits = Map.copyOf(cacheHits);
        cacheMisses = Map.copyOf(cacheMisses);
    }
}
