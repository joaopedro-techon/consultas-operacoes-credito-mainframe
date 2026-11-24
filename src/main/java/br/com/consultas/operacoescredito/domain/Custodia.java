package br.com.consultas.operacoescredito.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Custódia em que uma operação de crédito está registrada. O código é o mesmo no cache de custódia, na rota
 * do gateway da fonte primária e no campo {@code custodia} do bloco {@code operacao}. A ordem de declaração não é a
 * precedência: ela fica em {@link PrecedenciaDeCustodia}.
 */
public enum Custodia {

    SF,
    F5,
    OD,
    NSF;

    /** Nome do campo do bloco {@code operacao} que traz a custódia. */
    public static final String CAMPO = "custodia";

    public String codigo() {
        return name();
    }

    /** Custódia cujo código é {@code codigo} (sensível a maiúsculas). */
    public static Optional<Custodia> porCodigo(String codigo) {
        return Arrays.stream(values()).filter(custodia -> custodia.name().equals(codigo)).findFirst();
    }
}
