# Capacidade: ECS Fargate e mainframe

Modelo de capacidade do serviço `fonte-primaria` (itens 5, 6 e 7 de `docs/prompt_detalhes_ajustes_3.md`), com a arquitetura do ADR-0015. A demanda total do modelo é de 60.000 req/s, dos quais 10% vêm a este serviço; a parte da visão consolidada (task, DynamoDB, cache de blocos) fica no repositório `consultas-operacoes-credito` (ADR-0017).

Legenda, aplicada a todos os números:
- **(P) premissa:** valor assumido, a confirmar com dados de produção.
- **(E) estimativa:** derivada das premissas.
- **(M) medido:** a preencher com o k6 (`carga/README.md`), seção 6.

Nada aqui foi medido ainda.

## Resumo

| | `fonte-primaria` |
| --- | --- |
| Demanda-alvo | 6.000 req/s (10% de 60.000) **(P)** |
| Perfil | I/O-bound (p50 220 ms por chamada) e limitado pela **cota** |
| Task | **1 vCPU / 2 GB** (heap 1,5 GB) **(E)** |
| RPS-alvo por task | **~28 consultas/s** admitidas (cota 500 chamadas/s ÷ 17,8 chamadas/consulta) **(E)** |
| Alvo `ALBRequestCountPerTarget` (por minuto) | **1.500** (25 × 60) |
| Tasks no pico | 3 (fixo pela cota) |
| min / max tasks | **2 / 3** (`max` = `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS`) |
| Cooldown out / in | 60 s / 600 s |
| Deploy | **100% / 50%** |
| Política extra | — |
| Vazão real da frota | **~84 consultas/s** com a cota de 1.500 chamadas/s (mix); ~250–295/s só OPERACAO |
| Cota de mainframe para os 6.000 req/s | **~107 mil chamadas/s** nominal, **~128 mil** com 20% de margem (71× a cota atual) **(E)** |

Ajustes recomendados em resiliência (detalhes na seção 4):
- **Já validados:**
  - bulkhead por custódia de **120**;
  - `fontePrimaria-cliente-conta` de **160**;
  - pool de **240**, com `max-conexoes-por-rota` também em **240** (um host só; já está assim no `application.yml` novo).
- **A mudar:**
  - `max-chamadas-simultaneas` fica em **16**, mas com um **teto de `limite_operacoes` de ~50** nessa rota;
  - rate limiter de **50 a cada 100 ms**, em vez de 500 a cada 1 s;
  - retry de **2 tentativas** (hoje 3); o `prazo-minimo-restante` de **600 ms** previsto está adequado (≥ p99 de uma tentativa);
  - circuito por janela de **tempo de 10 s**.
- **Infraestrutura:** API Gateway com throttle por método e timeout da integração (seção 5).

## 1. Premissas

| Premissa | Valor | Tipo |
| --- | --- | --- |
| Demanda | 6.000 req/s na fonte primária (10% de 60.000) | (P) |
| Mix por tipo | 50% OPERACAO, 25% CLIENTE, 25% CONTA | (P) |
| Blocos por consulta (`expand`) | 4: `operacao` + `originacao` + 2. Dos 2 extras, 1 é bloco-lista com média de 2 páginas (1 a 3) | (P) |
| Operações por cliente/conta | 5 em média, no máximo 30 por custódia (1 página por lista). `limite_operacoes` vai até 500 | (P) |
| Latência do mainframe por chamada | p50 220 ms, p99 500 ms → lognormal σ = 0,353, **média 234 ms** | (P, dado) |
| Timeout por tentativa no mainframe | 700 ms | (P, dado) |
| Valkey | ~1 ms | (P) |
| Acerto do cache de custódia | 70% (TTL 24 h, compartilhado com a visão consolidada) | (P) |
| Retries com o mainframe saudável | +2% de chamadas | (P) |
| Um vCPU do Fargate | 1 hyperthread x86; utilização-alvo de 60% | (P) |

Lei de Little: **L = λ × W**. L é o número de itens em andamento (requisições, chamadas, conexões), λ é a vazão e W é o tempo de cada um.
## 2. Fonte primária: chamadas ao mainframe e cota

### 2.1 Chamadas por consulta

Cada página e cada tentativa é uma chamada e consome a cota (ADR-0014, ADR-0016).

**OPERACAO, com 4 blocos**

| Passo | Custódia em cache | Sem cache de custódia |
| --- | --- | --- |
| Descoberta: bloco `operacao` | 1 (na custódia lembrada) | 4 (fan-out nas 4 custódias) |
| `originacao` | 1 | 1 |
| Bloco-lista (ex.: `parcelas`, 1 a 3 páginas) | 2 | 2 |
| Bloco-objeto (ex.: `saldo`) | 1 | 1 |
| **Total** | **5** | **8** |

Com 70% de acerto do cache de custódia **(P)**: 5 + 3 × 0,3 = **5,9 chamadas (E)**. Com 95% de acerto, seriam 5,15.

**CLIENTE/CONTA, com N operações e 4 blocos**

A custódia de cada operação vem da própria lista, então não há descoberta.

```
chamadas = 4 listas × páginas por custódia  +  N × (operacao 1 + originacao 1 + lista 2 + objeto 1)
         = 4 × ⌈n_custódia / 30⌉            +  5N
```

| N | 1 | 5 (P) | 10 | 20 | 50 | 100 | 500 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Chamadas | 9 | **29** | 54 | 104 | 254 | 504 | ~2.520 |

As listas não vêm ordenadas: todas as páginas das 4 custódias são lidas antes de cortar em `limite_operacoes`. O limite reduz o detalhamento, mas não as listas.

**Mix**

0,5 × 5,9 + 0,5 × 29 = 17,45 chamadas por consulta. Com 2% de retry, **17,8 chamadas por consulta (E)**. O CLIENTE/CONTA responde por **83% das chamadas**.

### 2.2 Cota necessária × cota atual

| | Chamadas/s |
| --- | --- |
| OPERACAO (3.000/s × 5,9 × 1,02) | 18.054 |
| CLIENTE + CONTA (3.000/s × 29 × 1,02) | 88.740 |
| **Total para 6.000 consultas/s** | **≈ 106.800 (E)** |
| Com 20% de margem (picos, retries em incidente, páginas acima da média) | **≈ 128.000 (E)** |
| Cota atual | 1.500 |

O que 1.500 chamadas/s sustentam **(E)**:

| Tráfego | Consultas/s |
| --- | --- |
| Só OPERACAO, custódia sempre em cache (5 chamadas) | ~294. É a conta das ~300/s, mas ela só vale para OPERACAO |
| Só OPERACAO, 70% de acerto de custódia | ~249 |
| Só CLIENTE/CONTA com 5 operações | ~51 |
| **Mix 50/25/25** | **~84** |

Com a cota atual, **~98,6% dos 6.000 req/s da rota mainframe seriam 429**.

Para o pedido de aumento, há três caminhos, que podem ser combinados:
- **Pedir ~110 mil a 130 mil chamadas/s** (71× a cota atual).
- **Reduzir chamadas por consulta:**
  - menos blocos no `expand` dessa rota;
  - teto de `limite_operacoes` (seção 2.5);
  - acerto do cache de custódia perto de 100%. Ele é compartilhado com a visão consolidada, que o aquece.

  Cada operação a menos por cliente economiza 5 chamadas.
- **Mover CLIENTE/CONTA para a visão consolidada.** A sensibilidade abaixo mostra que é ali que está o custo.

| Cota necessária para 6.000/s (sem margem) | 3 operações/cliente | 5 operações/cliente | 10 operações/cliente |
| --- | --- | --- | --- |
| Expand com 2 blocos (`operacao`+`originacao`) | ~39 mil | ~52 mil | ~82 mil |
| Expand com 4 blocos | ~76 mil | **~107 mil** | ~183 mil |

### 2.3 Latência por consulta

Monte Carlo com a lognormal da seção 1, teto de 700 ms por tentativa e sem retry **(E)**:

| Consulta | p50 | p99 | Prazo |
| --- | --- | --- | --- |
| OPERACAO, custódia em cache, lista com 2 páginas | 0,69 s | 1,13 s | 2,5 s |
| OPERACAO, sem cache de custódia, lista com 3 páginas | 1,01 s | 1,50 s | 2,5 s |
| CLIENTE, 5 operações (16 simultâneas) | 1,00 s | 1,38 s | 6 s |
| CLIENTE, 50 operações (16 simultâneas) | 4,30 s | 4,72 s | 6 s |
| CLIENTE, 75 operações (16 simultâneas) | 6,13 s | **6,57 s → 504** | 6 s |
| Lista de 500 operações numa só custódia (17 páginas sequenciais) | 3,96 s | 4,85 s | — (só a lista) |

O pior caso da OPERACAO, com 1,5 s, ainda deixa espaço para um retry de 700 ms dentro dos 2,5 s.

### 2.4 Concorrência (Little) e dimensionamento por task

Com 3 tasks e a cota de 1.500 chamadas/s, cada task faz **500 chamadas/s** (= `cota ÷ max_tasks`).

| Chamadas em andamento por task | Média (234 ms) | Mainframe em p99 (500 ms) | Tudo no timeout (700 ms) |
| --- | --- | --- | --- |
| Total (500/s) | **117** | 250 | 350 |
| CLIENTE/CONTA (83% = 415/s) | 97 | 208 | 290 |
| Maior custódia, supondo 40% das chamadas (200/s) | 47 | 100 | 140 |

Validação dos valores que vão para o `application.yml` **(E)**:

- **Pool HTTP de 240 conexões.** Sustenta a cota inteira da task até uma latência média de 240 ÷ 500 = **480 ms**, perto do p99. Acima disso, o pool (e não o mainframe) passa a ser o limite, o que é a contrapressão desejada.
  - **`max-conexoes-por-rota` também precisa ser 240.** Todas as chamadas vão para o mesmo host do gateway, e com 50 o pool efetivo seria 50.
  - Regra sugerida, a validar na subida: `pool ≥ max(cota_por_task × 0,48 s, 2 × bulkhead_por_custódia)`.
- **Bulkhead por custódia de 120.** Na operação normal, a maior custódia usa ~47 vagas, e ~100 com o mainframe em p99.
  - Uma custódia degradada (tudo no timeout) ocupa no máximo 120, metade do pool, e as outras três continuam com 120 conexões.
  - Só duas custódias degradadas ao mesmo tempo esgotam o pool. Esse risco é aceito: a fila de conexão (`timeout-obter-conexao` 50 ms) vira 503 rápido.
- **`fontePrimaria-cliente-conta` de 160.** O CLIENTE/CONTA usa ~97 vagas em média e ~208 em p99. Ele só passa a limitar com o mainframe degradado, que é quando deve limitar, e sempre sobram ≥ 80 conexões do pool para OPERACAO.
- **Rate limiter de 500/s por task.** Hoje são `limit-for-period: 2000` a cada `1s`. Recomendação: **50 a cada 100 ms**, mesma vazão.
  - As 3 tasks renovam os períodos em instantes diferentes. Com períodos de 1 s, uma janela deslizante de 1 s pode ver até 2 × 500 por task, ou 3.000 na frota, se o mainframe medir a cota assim.
  - Com 100 ms o excesso cai para ≤ 10%, e a carga chega ao mainframe sem rajadas no início de cada segundo.

**Desperdício de cota.** A recusa acontece por chamada, no meio da consulta:
- um CLIENTE que já gastou 20 das 29 chamadas e recebe 429 perde as 20;
- perto da cota, a vazão útil cai abaixo dos ~84/s.

Recomendação (mudança de código, a decidir): admitir pela consulta. Uma forma é um rate limiter de consultas/s na entrada da rota, calibrado em `cota_por_task ÷ chamadas_médias`, com o de chamadas mantido como teto rígido. Outra é reservar as permissões estimadas logo depois da lista de operações.

### 2.5 `MAX_CHAMADAS_SIMULTANEAS` (item 5)

O semáforo vale por requisição CLIENTE/CONTA. O tempo do detalhamento é ~ `chamadas × 234 ms ÷ simultâneas`, mais as cadeias de páginas. Simulação do p99 da consulta inteira (lista + detalhamento) contra o prazo de 6 s **(E)**:

| Operações (N) | Chamadas | 16 simultâneas | 32 simultâneas | 64 simultâneas |
| --- | --- | --- | --- | --- |
| 5 | 29 | 1,38 s | 1,32 s | 1,32 s |
| 20 | 104 | 2,52 s | 1,85 s | 1,51 s |
| 50 | 254 | **4,72 s** | 2,92 s | 2,05 s |
| 75 | 379 | 6,57 s ✗ | 3,86 s | 2,50 s |
| 100 | 504 | 8,44 s ✗ | **4,77 s** | 2,97 s |
| 200 | 1.008 | 16,2 s ✗ | 8,75 s ✗ | 5,09 s |
| 500 | ~2.520 | 39 s ✗ | 20,6 s ✗ | 11,4 s ✗ |

Conclusões:
- **16 é suficiente para o caso típico** (5 operações: 1,4 s) e cabe no prazo até ~60 operações.
- **Para 500 operações, nenhum valor razoável resolve.** Seriam necessárias ~130 simultâneas, e a consulta custaria ~2.520 chamadas: 5 s da cota de uma task, ou 1,7 s da cota da frota inteira. Um único cliente grande derrubaria todos os outros com 429.

  O limite é a **cota**, não a concorrência. Subir o semáforo só faz cada consulta grande gastar a cota mais depressa.
- **Recomendação:**
  - Manter **16**, que é também o `permitted-number-of-calls-in-half-open-state` (ADR-0009).
  - Criar um **teto de `limite_operacoes` na rota fonte primária: ~50 (P), configurável.** Acima disso, 400 com Problem Details sugerindo a visão consolidada. Alternativa: um orçamento de chamadas por consulta (~300), verificado depois da lista.
  - Se o negócio exigir 100 operações nessa rota, subir para **32** e o meio-aberto junto, mantendo `fontePrimaria-cliente-conta` ≥ 5 × 32.
- **Encaixe com o bulkhead.** Com 16 por consulta, o bulkhead `fontePrimaria-cliente-conta` de 160 comporta 10 consultas grandes em fan-out pleno. A carga típica é ~14 consultas CLIENTE/CONTA em andamento por task (14/s × ~1 s), cada uma com ~7 chamadas em média, ou ~97 vagas. Cabe.

### 2.6 Circuit breaker e retry por custódia × bloco

São 44 instâncias por task (4 custódias × 9 blocos + 2 listas). Cada task divide 500 chamadas/s entre elas:
- uma instância quente (ex.: `fontePrimaria-SF-operacao`) recebe ~25 a 50 chamadas/s;
- uma fria (ex.: `fontePrimaria-OD-controles_iof`) recebe ≤ 1/s **(E)**.

**Circuit breaker**
- **Hoje:** `COUNT_BASED` de 50 chamadas, com mínimo de 20. A instância quente tem uma janela de 1 a 2 s e abre com uma rajada curta. A fria precisa de 20 a 50 s para decidir.
- **Recomendação:** `sliding-window-type: TIME_BASED`, `sliding-window-size: 10` (s), `minimum-number-of-calls: 10`. A taxa de falha de 50% e a espera de 5 s em aberto continuam. Assim todas as instâncias decidem na mesma escala de tempo.
- O timeout de 700 ms já faz a chamada lenta virar falha. Uma `slow-call-duration-threshold` de 600 ms com taxa de 80% abriria o circuito antes dos timeouts, numa degradação sistêmica (opcional).

**Retry**
- **`max-attempts` de 3 para 2.** Cada tentativa consome um token da cota (ADR-0014). Num incidente, 3 tentativas triplicam o consumo justamente quando o mainframe está mal, e as outras custódias e blocos recebem 429.
- **`prazo-minimo-restante` da fonte primária de 50 ms para 500 a 600 ms (≥ p99 de uma tentativa; o `application.yml` novo usa 600 ms).** Com 50 ms, o retry começa sem chance de terminar, gasta um token e vira 504 de qualquer forma.
  - O valor de 50 ms foi calculado para o prazo antigo de 150 ms (ADR-0006).
  - Se a propriedade for única para as duas rotas, ela precisa ficar por rota.
- **429 do próprio mainframe.** Ele indica cota global estourada (deploy fora do padrão, outro consumidor). Considere não retentar na mesma requisição, ou retentar só com backoff ≥ 200 ms. Retentar na hora agrava o estouro.

### 2.7 Task, escala e borda do serviço `fonte-primaria`

- **1 vCPU / 2 GB (E).** A CPU com a vazão da cota é pequena: ~500 chamadas/s × ~0,3 ms (parse do trancode + HTTP) **(P)** + ~28 consultas/s × ~2 ms ≈ **0,2 vCPU**.
  - Não se usa 0,5 vCPU por três motivos: a subida do Spring Boot fica 2× mais lenta, o 429 também gasta CPU (abaixo) e a economia é de centavos por hora (3 tasks).
  - Com 1 CPU a JVM escolhe o SerialGC. Com o heap de 1,5 GB e prazos de segundos, as pausas (dezenas de ms) não importam.
  - Memória: 240 chamadas em andamento com ≤ 30 ocorrências cada ocupam poucos MB. 2 GB é o mínimo do Fargate para 1 vCPU.
- **min 2 / max 3.** O `max` é o divisor da cota e entra na task como `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` (o `infra/ecs` preenche o autoscaling e a variável com o mesmo `var.fonte_primaria.max_tasks`). Com 2 tasks a frota tem 1.000 chamadas/s; é a capacidade parada aceita no ADR-0015.
- **Alvo de 1.500 requisições/min por task (25 req/s).** Com qualquer demanda perto da cota, o serviço fica no máximo, como esperado.
- **Deploy 100% / 50%.** Com 3 tasks, para uma, sobe a substituta e segue. A cota nunca é excedida.
- **O 429 precisa sair na borda.** Com 6.000 req/s de demanda e ~84 admitidas, cada uma das 3 tasks recusaria ~2.000 req/s.
  - Mesmo barato (~0,3 a 0,5 ms de CPU com o evento de consumo e o tracing **(P)**), isso ocupa ~0,6 a 1 vCPU só para recusar, e a task de 1 vCPU satura.
  - **Recomendação:** throttle por método no API Gateway para `GET /v1/operacoes-credito/fonte-primaria` (`aws_api_gateway_method_settings`, `throttling_rate_limit`) em ~100 a 120 req/s, perto da capacidade admitida mais uma margem. O 429 sai do gateway e as tasks só veem o que conseguem atender.
  - Sem isso, use 2 vCPU / 4 GB nessa rota e meça o custo do 429 (`carga/README.md`, passo 6).
- **Cenário com a cota aumentada para ~128 mil chamadas/s (E).**
  - Cerca de 2.000 chamadas/s por task de 1 vCPU (~60% da CPU), ou **~64 tasks** (`COTA_MAX_TASKS` = 64).
  - Por task: pool de ~960, bulkhead por custódia de ~480 e `fontePrimaria-cliente-conta` de ~640, pela mesma regra da seção 2.4.
  - O máximo fixo passa a custar 64 tasks paradas fora do pico. Nesse cenário vale a evolução do ADR-0015: cota ajustada pela contagem de tasks em execução.


## 3. Isolamento da visão consolidada

- **Limitação.** A cota do mainframe (rate limiter `fontePrimaria`, 429) roda neste serviço ECS e nunca recusa chamadas da visão consolidada, que roda em outro serviço e repositório. Bulkheads, pool e circuitos também são só deste serviço (ADR-0014/0015/0017).
- **O que os dois serviços compartilham:**
  - o **Valkey**, para a chave `custodia:<id>`. O CB `valkey` e o fail-open protegem a fonte primária de um Valkey lento;
  - o **ALB**: a regra `/v1/operacoes-credito/fonte-primaria*` deste serviço precisa ter prioridade sobre a `/v1/operacoes-credito*` da visão consolidada.

## 4. Ajustes recomendados no `application.yml`

| Propriedade | Valor previsto | Recomendação | Motivo |
| --- | --- | --- | --- |
| `consultas.fonte-primaria.pool.max-conexoes` | 240 | **240** ✓ | Cota da task × 480 ms (§2.4) |
| `consultas.fonte-primaria.pool.max-conexoes-por-rota` | 240 | **240** ✓ | Um host só: o limite por rota é o pool efetivo |
| bulkhead `fontePrimaria-<CUSTODIA>` | 120 | **120** ✓ | Metade do pool; normal ~47, p99 ~100 |
| bulkhead `fontePrimaria-cliente-conta` | 160 | **160** ✓ | Normal ~97; sobram ≥ 80 para OPERACAO |
| `fan-out.max-chamadas-simultaneas` | 16 | **16** + teto de `limite_operacoes` ~50 na fonte primária (ou 32 com teto de 100) | §2.5: o limite é a cota |
| CB `fontePrimaria` `permitted-number-of-calls-in-half-open-state` | 16 | = `max-chamadas-simultaneas` | ADR-0009 |
| CB `fontePrimaria` janela | COUNT_BASED 50 / mín. 20 | **TIME_BASED 10 s / mín. 10** | Instâncias quentes e frias na mesma escala de tempo (§2.6) |
| retry `fontePrimaria` `max-attempts` | 3 | **2** | Cada tentativa consome a cota |
| `consultas.fonte-primaria.retry.prazo-minimo-restante` | 600 ms | **500 a 600 ms** ✓ | ≥ p99 de uma tentativa; prazo novo de 2,5 s/6 s |
| ratelimiter `fontePrimaria` | cota ÷ max-tasks por 1 s | **(cota ÷ max-tasks) ÷ 10 por 100 ms** | Sem rajadas, sem 2× na janela deslizante |

## 5. Limites da plataforma fora da aplicação

- **API Gateway** (no repositório da visão consolidada):
  - **Timeout da integração:** `integration_timeout_ms` tem padrão de 1.000 ms, abaixo dos prazos da fonte primária (2,5 s e 6 s). O gateway cortaria antes da aplicação. A rota fonte primária precisa de **≥ 6.500 ms**.
  - **Throttle por método na rota fonte primária** (§2.7).
- **Fargate:** a cota de vCPU On-Demand da conta precisa cobrir as 3 tasks de 1 vCPU (o deploy a 100% não sobe tasks extras).
- **SQS** (evento de consumo): um `SendMessageBatch` a cada até 10 consultas admitidas, mais as recusadas com 429 se o throttle não sair na borda.
- **OTel Collector.** Com o tracing a 100%, recebe os spans de todas as requisições para fazer o tail sampling, inclusive as recusadas com 429. Ele precisa ser dimensionado à parte.

## 6. Como validar com o k6

Procedimento completo em `carga/README.md`: limitar o container a 1 vCPU / 2 GB, latência do mainframe no WireMock, rampa até o joelho e extrapolação para o Fargate.

| Número | Como medir | Medido (M) |
| --- | --- | --- |
| Chamadas ao mainframe por consulta (E: 5,9 / 29) | Painel "Chamadas ao mainframe × cota" ÷ RPS, por tipo (`MIX_*`) | |
| Chamadas em andamento e vagas nos bulkheads (E: 117 por task) | Painéis de bulkhead com a latência lognormal no WireMock e `COTA_MAX_TASKS=1` | |
| Custo do 429 (P: 0,3 a 0,5 ms) | `fonte-primaria.js` com `RPS=150`, `MAX_429=0.9`; CPU ÷ (429/s) | |
| p99 da fonte primária por N operações (§2.5) | `LIMITE_OPERACOES` e CPFs com mais operações; ver `duracao_200{tipo_consulta:CLIENTE}` | |
| Acerto do cache de custódia (P: 70%) | Produção: `consultas_custodia_descoberta_total{resultado="cache"}` | |