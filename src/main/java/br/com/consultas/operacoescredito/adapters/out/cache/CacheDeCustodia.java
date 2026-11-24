package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Custodia;

import java.util.Map;
import java.util.Optional;

/**
 * Cache de custódia (ADR-0012): em qual custódia cada operação foi encontrada, com chave
 * {@code custodia:{id_operacao}}. Guarda só o endereço, nunca dados da operação (ADR-0002). Fail-open: falha
 * equivale a "não sei" (refaz a descoberta) e a "não gravado". Chave, valor e TTL são contrato com o serviço da
 * visão consolidada, que usa o mesmo Valkey (ADR-0017).
 */
public interface CacheDeCustodia {

    Optional<Custodia> buscar(long idOperacao);

    /** Best-effort, fora da requisição. */
    void gravar(long idOperacao, Custodia custodia);

    /** Várias de uma vez (uma rodada de gravação), best-effort, fora da requisição. */
    void gravarTodas(Map<Long, Custodia> custodias);

    /** Esquece a custódia (a operação não está mais nela); best-effort, fora da requisição. */
    void remover(long idOperacao);

    /** Chave da custódia de uma operação no Valkey, ex.: {@code custodia:100000001}. */
    static String chave(long idOperacao) {
        return "custodia:" + idOperacao;
    }
}
