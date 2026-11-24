#!/usr/bin/env bash
# Relatório em Markdown de uma verificação de contrato que divergiu (job diário, .github/workflows/contrato-diario.yml):
# testes que falharam e o diff entre a cópia versionada do contrato, o catálogo local e o contrato baixado da main
# de consultas-operacoes-credito. Corpo da issue aberta ou atualizada pelo job; cortado abaixo do limite do GitHub.
#
# Uso: scripts/relatorio-contrato.sh <diretorio-do-contrato-baixado> [url-da-execucao]
set -uo pipefail

baixado="${1:?diretório do contrato baixado}"
execucao="${2:-}"
limite=60000

secao_diff() {
  local titulo="$1" antes="$2" depois="$3"
  echo "### $titulo"
  echo
  if [[ ! -f "$depois" ]]; then
    echo "\`$depois\` não foi baixado."
  elif diff -q "$antes" "$depois" >/dev/null; then
    echo "Sem diferença."
  else
    echo '```diff'
    diff -u --label "$antes" --label "$depois" "$antes" "$depois"
    echo '```'
  fi
  echo
}

{
  echo "O contrato da \`main\` de \`joaopedro-techon/consultas-operacoes-credito\` diverge deste serviço (ADR-0017)."
  [[ -n "$execucao" ]] && echo "Execução: $execucao"
  echo
  echo "Mudanças de contrato começam lá e são alinhadas aqui: ajuste o serviço e/ou o \`catalogo-campos.yml\`,"
  echo "rode \`scripts/baixar-contrato.sh\` para atualizar a cópia em \`src/test/resources/contrato\` e abra um PR."
  echo "A issue é fechada pelo job diário quando a verificação voltar a passar."
  echo
  echo "## Testes que falharam"
  echo
  falhas=$(grep -h -A4 -E '<<< (FAILURE|ERROR)!' target/surefire-reports/*.txt target/failsafe-reports/*.txt 2>/dev/null)
  if [[ -n "$falhas" ]]; then
    echo '```'
    echo "$falhas" | head -n 200
    echo '```'
  else
    echo "Nenhum relatório de teste com falha (o download do contrato pode ter falhado; veja a execução)."
  fi
  echo
  echo "## Diferenças"
  echo
  secao_diff "openapi.yaml (cópia versionada x main)" src/test/resources/contrato/openapi.yaml "$baixado/openapi.yaml"
  secao_diff "catalogo-campos.yml (local x main)" src/main/resources/catalogo-campos.yml "$baixado/catalogo-campos.yml"
} > relatorio-contrato.completo.md

if (( $(wc -c < relatorio-contrato.completo.md) > limite )); then
  head -c "$limite" relatorio-contrato.completo.md
  printf '\n\n_Relatório cortado em %s bytes; o completo está nos logs da execução._\n' "$limite"
else
  cat relatorio-contrato.completo.md
fi
