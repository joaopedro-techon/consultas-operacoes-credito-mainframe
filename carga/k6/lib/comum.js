// Partes comuns dos cenários de carga: massa local, montagem dos cenários e das requisições.
// Massa: stubs do gateway em wiremock/mappings (operações 100000001..100000007 e 100000101..100000114).
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const CONSUMIDOR = __ENV.CONSUMIDOR || 'carga-k6';

const intervalo = (de, ate) => Array.from({ length: ate - de + 1 }, (_, i) => String(de + i));

export const OPERACOES = [...intervalo(100000001, 100000007), ...intervalo(100000101, 100000114)];

export const CPF_CNPJ = ['11222333000181', '52998224725', '12ABC34501DE35', '11144477735'];

export const CONTAS = ['434115000055501', '434115000065874', '434115000099991', '434115000077712', '434115000088823'];

// ~4 blocos por consulta (operacao e originacao vêm sempre + 2), premissa do modelo de capacidade
export const EXPANDS = [
  'parcelas,saldo',
  'amortizacoes,plano',
  'parcelas,componentes',
  'recebimentos,controles_iof',
  'saldo,plano',
];

// Mix do modelo (docs/capacidade-ecs-fargate.md): 50% OPERACAO, 25% CLIENTE, 25% CONTA
const MIX = {
  operacao: Number(__ENV.MIX_OPERACAO || 0.5),
  cliente: Number(__ENV.MIX_CLIENTE || 0.25),
  conta: Number(__ENV.MIX_CONTA || 0.25),
};

export const respostas429 = new Counter('respostas_429');
export const taxa429 = new Rate('taxa_429');
export const respostasParciais = new Rate('respostas_parciais');
export const respostas5xx = new Counter('respostas_5xx');
// Latência só das respostas 200: o 429 imediato da fonte primária não pode puxar o p99 para baixo
export const duracao200 = new Trend('duracao_200', true);

const sortear = (lista) => lista[Math.floor(Math.random() * lista.length)];

/**
 * Cenários com chegada aberta (a taxa não cai quando a aplicação fica lenta, como em produção).
 * MODO=constante (padrão): constant-arrival-rate em RPS por DURACAO.
 * MODO=rampa: ramping-arrival-rate de RPS_INICIAL até RPS em degraus, para achar o joelho da curva.
 */
export function cenarios(rpsPadrao) {
  const rps = Number(__ENV.RPS || rpsPadrao);
  const modo = __ENV.MODO || 'constante';
  const duracao = __ENV.DURACAO || '2m';
  const degraus = Number(__ENV.DEGRAUS || 6);
  const duracaoDegrau = __ENV.DURACAO_DEGRAU || '1m';
  const rpsInicial = Number(__ENV.RPS_INICIAL || Math.max(1, Math.round(rps / degraus)));
  const preAlocados = Number(__ENV.VUS || 200);
  const maxVus = Number(__ENV.MAX_VUS || 2000);

  const resultado = {};
  for (const [tipo, fatia] of Object.entries(MIX)) {
    if (fatia <= 0) continue;
    const taxa = Math.max(1, Math.round(rps * fatia));
    const base = { exec: tipo, preAllocatedVUs: Math.ceil(preAlocados * fatia), maxVUs: Math.ceil(maxVus * fatia), timeUnit: '1s' };
    if (modo === 'rampa') {
      const inicial = Math.max(1, Math.round(rpsInicial * fatia));
      const stages = Array.from({ length: degraus }, (_, i) => ({
        target: Math.round(inicial + ((taxa - inicial) * (i + 1)) / degraus),
        duration: duracaoDegrau,
      }));
      resultado[tipo] = { ...base, executor: 'ramping-arrival-rate', startRate: inicial, stages };
    } else {
      resultado[tipo] = { ...base, executor: 'constant-arrival-rate', rate: taxa, duration: duracao };
    }
  }
  return resultado;
}

function consultar(caminho, tipo, params) {
  const query = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&');
  const resposta = http.get(`${BASE_URL}${caminho}?${query}`, {
    headers: { 'x-tipo-consulta': tipo, 'x-consumidor': CONSUMIDOR },
    tags: { tipo_consulta: tipo, name: `${caminho} ${tipo}` },
  });
  const e429 = resposta.status === 429;
  respostas429.add(e429 ? 1 : 0, { tipo_consulta: tipo });
  taxa429.add(e429, { tipo_consulta: tipo });
  if (resposta.status >= 500) respostas5xx.add(1, { tipo_consulta: tipo, status: String(resposta.status) });
  if (resposta.status === 200) {
    duracao200.add(resposta.timings.duration, { tipo_consulta: tipo });
    respostasParciais.add(Boolean(resposta.headers['X-Blocos-Indisponiveis']), { tipo_consulta: tipo });
  }
  check(resposta, { 'status 200 ou 429': (r) => r.status === 200 || r.status === 429 });
  return resposta;
}

export function consultaOperacao(caminho) {
  return consultar(caminho, 'OPERACAO', { id_operacao: sortear(OPERACOES), expand: sortear(EXPANDS) });
}

export function consultaCliente(caminho) {
  return consultar(caminho, 'CLIENTE', {
    cpf_cnpj: sortear(CPF_CNPJ),
    expand: sortear(EXPANDS),
    limite_operacoes: __ENV.LIMITE_OPERACOES,
  });
}

export function consultaConta(caminho) {
  return consultar(caminho, 'CONTA', {
    conta: sortear(CONTAS),
    expand: sortear(EXPANDS),
    limite_operacoes: __ENV.LIMITE_OPERACOES,
  });
}
