# consultas-operacoes-credito-mainframe

## Stack

**Java 25** and **Spring Boot 4.x**. This service answers only the primary source route (`/v1/operacoes-credito/fonte-primaria`, the mainframe gateway). The consolidated view (DynamoDB, block cache) lives in `joaopedro-techon/consultas-operacoes-credito` (ADR-0017). The custody cache key `custodia:{id_operacao}` (TTL 24h) is a contract shared with that repo.

## Agent skills

### Issue tracker

Issues and specs are tracked in GitHub Issues (`joaopedro-techon/consultas-operacoes-credito-mainframe`) through the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.
