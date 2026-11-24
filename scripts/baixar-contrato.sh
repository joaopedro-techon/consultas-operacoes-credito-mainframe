#!/usr/bin/env bash
# Baixa o contrato HTTP da main de consultas-operacoes-credito (ADR-0017): o openapi.yaml e o catalogo-campos.yml.
# Repositório público: URL raw, sem token.
#
# Uso: scripts/baixar-contrato.sh [diretorio]
#   sem argumento, atualiza a cópia versionada em src/test/resources/contrato (usada pelo build local);
#   o CI baixa em target/contrato e roda o Maven com -Dcontrato.dir=target/contrato.
set -euo pipefail

destino="${1:-src/test/resources/contrato}"
origem="${CONTRATO_ORIGEM:-https://raw.githubusercontent.com/joaopedro-techon/consultas-operacoes-credito/main}"

mkdir -p "$destino"
curl -fsSL --retry 3 --retry-all-errors -o "$destino/openapi.yaml" "$origem/contrato/openapi.yaml"
curl -fsSL --retry 3 --retry-all-errors -o "$destino/catalogo-campos.yml" "$origem/src/main/resources/catalogo-campos.yml"
echo "Contrato de $origem em $destino"
