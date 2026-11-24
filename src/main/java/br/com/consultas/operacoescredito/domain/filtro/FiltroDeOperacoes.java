package br.com.consultas.operacoescredito.domain.filtro;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.catalogo.Campo;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import br.com.consultas.operacoescredito.domain.catalogo.TipoCampo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Filtro em memória sobre as operações devolvidas pela fonte (seção 2.6), tipado pelo
 * catálogo de campos (ADR-0005).
 *
 * <p>Cada condição tem um <em>alvo</em>: a lista cujos itens ela seleciona. Em bloco-lista
 * ({@code parcelas}, ...) o alvo são os itens do bloco; em bloco-objeto, a primeira lista do caminho
 * ({@code saldo.saldo_parcelas}). Filtrar um alvo não mexe em nenhum outro bloco nem lista. Em
 * {@code operacao} e {@code originacao} o alvo é a própria operação: quem não atende sai de {@code data}.
 * Campos de listas aninhadas dentro do alvo são comparados por existência (algum item atende).
 *
 * <p>As condições ligadas por {@code ;} no nível de cima podem ter alvos diferentes; dentro de um
 * {@code ,} (ou) todas precisam ter o mesmo alvo, já que não há como "ou" remover itens de listas distintas.
 * {@code operacao} e {@code originacao} contam como um só alvo (a operação), então podem ser ligados por "ou".
 */
public final class FiltroDeOperacoes {

    /** Filtro que mantém tudo. */
    public static final FiltroDeOperacoes NENHUM = new FiltroDeOperacoes(Map.of());

    private static final Set<Bloco> FILTRAM_A_OPERACAO = Bloco.semprePresentes();

    private final Map<Alvo, Predicate<Map<?, ?>>> restricoes;

    private FiltroDeOperacoes(Map<Alvo, Predicate<Map<?, ?>>> restricoes) {
        this.restricoes = restricoes;
    }

    /**
     * Valida a expressão contra o catálogo e os blocos pedidos e prepara a avaliação.
     *
     * @throws FiltroInvalidoException campo desconhecido, bloco fora do {@code expand}, operador ou
     *                                 argumento incompatível com o tipo do campo
     */
    public static FiltroDeOperacoes compilar(ExpressaoFiltro expressao, CatalogoDeCampos catalogo, Set<Bloco> blocos) {
        var compilador = new Compilador(catalogo, blocos);
        var restricoes = new LinkedHashMap<Alvo, Predicate<Map<?, ?>>>();
        for (var condicao : conjuncoes(expressao)) {
            var compilada = compilador.compilar(condicao);
            restricoes.merge(compilada.alvo(), compilada.predicado(), Predicate::and);
        }
        return new FiltroDeOperacoes(restricoes);
    }

    /** Aplica o filtro: remove operações e itens de listas que não atendem, sem alterar o restante. */
    public List<OperacaoConsultada> aplicar(List<OperacaoConsultada> operacoes) {
        if (restricoes.isEmpty()) {
            return operacoes;
        }
        return operacoes.stream().filter(this::operacaoAtende).map(this::filtrarListas).toList();
    }

    /** As condições de {@code operacao}/{@code originacao} são avaliadas sobre os blocos da operação. */
    private boolean operacaoAtende(OperacaoConsultada operacao) {
        var daOperacao = restricoes.get(Alvo.OPERACAO);
        return daOperacao == null || daOperacao.test(operacao.blocos());
    }

    private OperacaoConsultada filtrarListas(OperacaoConsultada operacao) {
        var blocos = new EnumMap<Bloco, Object>(Bloco.class);
        blocos.putAll(operacao.blocos());
        restricoes.forEach((alvo, predicado) -> {
            if (!alvo.daOperacao() && blocos.get(alvo.bloco()) != null) {
                blocos.put(alvo.bloco(), filtrarEm(blocos.get(alvo.bloco()), alvo.caminhoLista(), predicado));
            }
        });
        return new OperacaoConsultada(blocos);
    }

    /** Desce pelo caminho até a lista alvo e mantém só os itens que atendem, copiando o que mudou. */
    private static Object filtrarEm(Object conteudo, List<String> caminho, Predicate<Map<?, ?>> predicado) {
        if (caminho.isEmpty()) {
            if (conteudo instanceof List<?> lista) {
                return lista.stream().filter(item -> item instanceof Map<?, ?> mapa && predicado.test(mapa)).toList();
            }
            return conteudo;
        }
        if (conteudo instanceof Map<?, ?> mapa && mapa.get(caminho.getFirst()) != null) {
            var copia = new LinkedHashMap<Object, Object>(mapa);
            copia.put(caminho.getFirst(), filtrarEm(mapa.get(caminho.getFirst()), caminho.subList(1, caminho.size()),
                    predicado));
            return copia;
        }
        return conteudo;
    }

    private static List<ExpressaoFiltro> conjuncoes(ExpressaoFiltro expressao) {
        if (expressao instanceof ExpressaoFiltro.E(var operandos)) {
            var todas = new ArrayList<ExpressaoFiltro>();
            operandos.forEach(operando -> todas.addAll(conjuncoes(operando)));
            return todas;
        }
        return List.of(expressao);
    }

    /**
     * Lista cujos itens uma condição seleciona. Condições de {@code operacao} e {@code originacao} têm todas o
     * mesmo alvo, {@link #OPERACAO} (a própria operação), e podem ser ligadas por {@code ,} (ou) entre si.
     *
     * @param bloco        bloco da lista alvo; {@code null} em {@link #OPERACAO}
     * @param caminhoLista caminho, dentro do bloco, até a lista alvo (vazio: os itens do bloco-lista)
     * @param daOperacao   {@code true} só em {@link #OPERACAO}: o predicado recebe o mapa de blocos da operação
     */
    private record Alvo(Bloco bloco, List<String> caminhoLista, boolean daOperacao) {

        static final Alvo OPERACAO = new Alvo(null, List.of(), true);

        String descricao() {
            if (daOperacao) {
                return "operacao/originacao";
            }
            return caminhoLista.isEmpty() ? bloco.nome() : bloco.nome() + "." + String.join(".", caminhoLista);
        }
    }

    private record Condicao(Alvo alvo, Predicate<Map<?, ?>> predicado) {
    }

    private record Compilador(CatalogoDeCampos catalogo, Set<Bloco> blocos) {

        Condicao compilar(ExpressaoFiltro expressao) {
            return switch (expressao) {
                case ExpressaoFiltro.Comparacao comparacao -> comparacao(comparacao);
                case ExpressaoFiltro.E(var operandos) -> logica(operandos, ";", Predicate::and);
                case ExpressaoFiltro.Ou(var operandos) -> logica(operandos, ",", Predicate::or);
            };
        }

        private Condicao logica(List<ExpressaoFiltro> operandos, String operador,
                                BinaryOperator<Predicate<Map<?, ?>>> combinar) {
            var condicoes = operandos.stream().map(this::compilar).toList();
            var alvo = condicoes.getFirst().alvo();
            var outro = condicoes.stream().map(Condicao::alvo).filter(a -> !a.equals(alvo)).findFirst();
            if (outro.isPresent()) {
                throw new FiltroInvalidoException("Condições ligadas por '" + operador + "' dentro de um ',' (ou) "
                        + "precisam filtrar a mesma lista; o filter mistura '" + alvo.descricao() + "' e '"
                        + outro.get().descricao() + "'");
            }
            var predicado = condicoes.stream().map(Condicao::predicado).reduce(combinar).orElseThrow();
            return new Condicao(alvo, predicado);
        }

        private Condicao comparacao(ExpressaoFiltro.Comparacao comparacao) {
            var seletor = comparacao.seletor();
            var ponto = seletor.indexOf('.');
            if (ponto < 0) {
                throw new FiltroInvalidoException(
                        "Campo '" + seletor + "' do filter deve ter o formato <bloco>.<campo> (ex.: parcelas.situacao_parcela)");
            }
            var bloco = Bloco.porNome(seletor.substring(0, ponto)).orElseThrow(() -> new FiltroInvalidoException(
                    "Bloco '" + seletor.substring(0, ponto) + "' inválido no filter. Blocos válidos: "
                            + Arrays.stream(Bloco.values()).map(Bloco::nome).collect(Collectors.joining(", "))));
            if (!blocos.contains(bloco)) {
                throw new FiltroInvalidoException(
                        "Campo '" + seletor + "' do filter é do bloco '" + bloco.nome() + "', que não está no expand");
            }
            var caminho = seletor.substring(ponto + 1);
            var campo = catalogo.campo(bloco, caminho).orElseThrow(() -> new FiltroInvalidoException(
                    "Campo '" + seletor + "' do filter não existe no catálogo de campos"));
            if (campo.tipo().composto()) {
                throw new FiltroInvalidoException("Campo '" + seletor + "' do filter é do tipo "
                        + campo.tipo().name().toLowerCase() + "; filtre por um dos seus campos");
            }
            var operador = comparacao.operador();
            if (operador.deOrdem() && !ordenavel(campo.tipo())) {
                throw new FiltroInvalidoException("Campo '" + seletor + "' do filter é do tipo "
                        + campo.tipo().name().toLowerCase() + " e só aceita ==, !=, =in= e =out=");
            }
            List<Comparable<?>> argumentos = comparacao.argumentos().stream()
                    .<Comparable<?>>map(argumento -> argumento(seletor, campo, argumento))
                    .toList();
            var partes = List.of(caminho.split("\\.", -1));
            var alvo = alvo(seletor, bloco, partes);
            var relativo = partes.subList(alvo.caminhoLista().size(), partes.size());
            Predicate<Map<?, ?>> predicado = item -> atende(valores(item, relativo), campo.tipo(), operador, argumentos);
            if (alvo.daOperacao()) {
                // Avaliado sobre os blocos da operação: sem o bloco, a operação não atende
                Predicate<Map<?, ?>> noBloco = predicado;
                predicado = blocosDaOperacao -> blocosDaOperacao.get(bloco) instanceof Map<?, ?> conteudo
                        && noBloco.test(conteudo);
            }
            return new Condicao(alvo, predicado);
        }

        private Alvo alvo(String seletor, Bloco bloco, List<String> partes) {
            if (catalogo.definicao(bloco).estrutura() == EstruturaBloco.LISTA) {
                return new Alvo(bloco, List.of(), false);
            }
            for (int i = 1; i < partes.size(); i++) {
                var prefixo = partes.subList(0, i);
                var tipo = catalogo.campo(bloco, String.join(".", prefixo)).map(Campo::tipo);
                if (tipo.filter(t -> t == TipoCampo.LISTA).isPresent()) {
                    return new Alvo(bloco, List.copyOf(prefixo), false);
                }
            }
            if (FILTRAM_A_OPERACAO.contains(bloco)) {
                return Alvo.OPERACAO;
            }
            throw new FiltroInvalidoException("Campo '" + seletor + "' do filter não pode ser filtrado: no bloco '"
                    + bloco.nome() + "' só campos de listas aninhadas aceitam filtro (filtro que remove a operação "
                    + "inteira só vale para operacao e originacao)");
        }

        private static boolean ordenavel(TipoCampo tipo) {
            return switch (tipo) {
                case NUMERO, DECIMAL, DATA, DATA_HORA -> true;
                default -> false;
            };
        }

        private static Comparable<?> argumento(String seletor, Campo campo, String argumento) {
            return converter(campo.tipo(), argumento).orElseThrow(() -> new FiltroInvalidoException(
                    "Valor '" + argumento + "' inválido para o campo '" + seletor + "' do filter, do tipo "
                            + campo.tipo().name().toLowerCase() + exemplo(campo.tipo())));
        }

        private static String exemplo(TipoCampo tipo) {
            return switch (tipo) {
                case NUMERO -> " (ex.: 4)";
                case DECIMAL -> " (ex.: 150.75)";
                case DATA -> " (formato yyyy-MM-dd)";
                case DATA_HORA -> " (formato yyyy-MM-ddTHH:mm:ss)";
                case BOOLEANO -> " (true ou false)";
                default -> "";
            };
        }
    }

    /** Valores do campo a partir do item alvo; listas no meio do caminho contribuem com todos os seus itens. */
    private static List<Object> valores(Object atual, List<String> caminho) {
        if (atual instanceof List<?> lista) {
            return lista.stream().flatMap(item -> valores(item, caminho).stream()).toList();
        }
        if (caminho.isEmpty()) {
            return atual == null ? List.of() : List.of(atual);
        }
        if (atual instanceof Map<?, ?> mapa) {
            return valores(mapa.get(caminho.getFirst()), caminho.subList(1, caminho.size()));
        }
        return List.of();
    }

    private static boolean atende(List<Object> valores, TipoCampo tipo, OperadorComparacao operador,
                                  List<Comparable<?>> argumentos) {
        var comparaveis = valores.stream().map(valor -> converter(tipo, valor)).flatMap(Optional::stream).toList();
        if (comparaveis.isEmpty()) {
            return operador.aceitaAusencia();
        }
        return comparaveis.stream().anyMatch(valor -> atende(valor, operador, argumentos));
    }

    private static boolean atende(Comparable<?> valor, OperadorComparacao operador, List<Comparable<?>> argumentos) {
        return switch (operador) {
            case IGUAL -> comparar(valor, argumentos.getFirst()) == 0;
            case DIFERENTE -> comparar(valor, argumentos.getFirst()) != 0;
            case MENOR -> comparar(valor, argumentos.getFirst()) < 0;
            case MENOR_OU_IGUAL -> comparar(valor, argumentos.getFirst()) <= 0;
            case MAIOR -> comparar(valor, argumentos.getFirst()) > 0;
            case MAIOR_OU_IGUAL -> comparar(valor, argumentos.getFirst()) >= 0;
            case EM -> argumentos.stream().anyMatch(argumento -> comparar(valor, argumento) == 0);
            case FORA -> argumentos.stream().noneMatch(argumento -> comparar(valor, argumento) == 0);
        };
    }

    @SuppressWarnings("unchecked")
    private static int comparar(Comparable<?> valor, Comparable<?> argumento) {
        return ((Comparable<Object>) valor).compareTo(argumento);
    }

    /** Valor no tipo do catálogo; vazio quando não é conversível (argumento inválido ou dado fora do formato). */
    private static Optional<Comparable<?>> converter(TipoCampo tipo, Object valor) {
        var texto = valor.toString();
        try {
            return switch (tipo) {
                case NUMERO, DECIMAL -> Optional.of(new BigDecimal(texto));
                case DATA -> Optional.of(LocalDate.parse(texto));
                case DATA_HORA -> Optional.of(LocalDateTime.parse(texto));
                case TEXTO -> Optional.of(texto);
                case BOOLEANO -> "true".equals(texto) || "false".equals(texto)
                        ? Optional.of(Boolean.valueOf(texto)) : Optional.empty();
                case OBJETO, LISTA -> Optional.empty();
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
