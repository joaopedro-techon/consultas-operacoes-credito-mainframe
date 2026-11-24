#!/bin/bash
# Cria a fila SQS dos eventos de consumo (ADR-0004), o único recurso do LocalStack neste serviço
# (docker-compose e Testcontainers esperam EVENTOS_CONSUMO_PRONTA).
set -euo pipefail

FILA="${CONSULTAS_EVENTOS_FILA:-consultas-eventos-consumo}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "Criando fila ${FILA}"
awslocal sqs create-queue --queue-name "${FILA}" > /dev/null
echo "EVENTOS_CONSUMO_PRONTA"
