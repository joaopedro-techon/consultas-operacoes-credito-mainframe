package br.com.consultas.operacoescredito.domain;

import java.util.Objects;
import java.util.OptionalInt;

/** Como as operações de crédito são localizadas: uma variante por tipo de consulta. */
public sealed interface CriterioConsulta {

    /** Maior valor aceito em {@code limite_operacoes}. */
    int LIMITE_OPERACOES_MAXIMO = 500;

    TipoConsulta tipo();

    record PorOperacao(long idOperacao) implements CriterioConsulta {

        public PorOperacao {
            if (idOperacao <= 0) {
                throw new IllegalArgumentException("id_operacao deve ser positivo");
            }
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.OPERACAO;
        }
    }

    /**
     * Operações do cliente, das mais recentes para as mais antigas por data de contratação.
     *
     * @param cpfCnpj          CPF ou CNPJ (numérico ou alfanumérico) com DV válido
     * @param limiteOperacoes  quantas operações devolver; vazio devolve todas
     */
    record PorCliente(String cpfCnpj, OptionalInt limiteOperacoes) implements CriterioConsulta {

        public PorCliente {
            if (!CpfCnpj.valido(cpfCnpj)) {
                throw new IllegalArgumentException("cpf_cnpj inválido");
            }
            validarLimite(limiteOperacoes);
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.CLIENTE;
        }
    }

    /**
     * Operações vinculadas à conta, das mais recentes para as mais antigas por data de contratação.
     *
     * @param conta            número da conta, de 1 a {@value #TAMANHO_MAXIMO_CONTA} dígitos
     * @param limiteOperacoes  quantas operações devolver; vazio devolve todas
     */
    record PorConta(String conta, OptionalInt limiteOperacoes) implements CriterioConsulta {

        /**
         * Maior número de dígitos aceito na conta: folga sobre os 15 dígitos das contas da massa e muito abaixo de qualquer limite de chave, para que uma conta absurda seja 400 e não uma falha na
         * fonte.
         */
        public static final int TAMANHO_MAXIMO_CONTA = 20;

        public PorConta {
            if (conta == null || conta.isEmpty() || conta.length() > TAMANHO_MAXIMO_CONTA
                    || !conta.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("conta deve ter de 1 a " + TAMANHO_MAXIMO_CONTA + " dígitos");
            }
            validarLimite(limiteOperacoes);
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.CONTA;
        }
    }

    private static void validarLimite(OptionalInt limiteOperacoes) {
        Objects.requireNonNull(limiteOperacoes, "limiteOperacoes");
        limiteOperacoes.ifPresent(limite -> {
            if (limite < 1 || limite > LIMITE_OPERACOES_MAXIMO) {
                throw new IllegalArgumentException("limite_operacoes deve estar entre 1 e " + LIMITE_OPERACOES_MAXIMO);
            }
        });
    }
}
