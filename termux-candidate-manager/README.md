# IBRA Evolution — Candidate Manager (Termux)

This folder contains a standalone CLI helper for the existing Termux service. It does not overwrite `evolution_service.py` or `tool_catalog.py`.

## Install

Copy `candidate_manager.py` into:
`~/IBRA-Evolution/ibra-evolution-termux/candidate_manager.py`

Run commands from that directory.

## Commands

- `python candidate_manager.py list` — list JSON candidates under `pending_candidates/`.
- `python candidate_manager.py show outil_calcul_test` — inspect the complete candidate record.
- `python candidate_manager.py review outil_calcul_test --validate` — execute all candidate cases with the catalog's restricted engine; marks `VALIDATED` only if every expected result matches, otherwise `REJECTED`.
- `python candidate_manager.py review outil_calcul_test --reject` — explicitly reject a candidate.
- `python candidate_manager.py install outil_calcul_test` — install only a candidate marked `VALIDATED` with an all-passing test report, using the catalog's own installer/versioning.
- `python candidate_manager.py rollback outil_calcul_test` — asks the catalog module to roll back the tool.

## Candidate case format

Each case must contain `input` and `expected`, for example:
`{"input":{"a":12,"b":5},"expected":{"somme":17,"produit":60}}`.

## Safety and compatibility notes

- This helper never uses Python `exec` or `eval`; tests use `tool_catalog.engine.execute_code`, the restricted engine used by the catalog.
- Installation requires the candidate status to be `VALIDATED` and a saved all-passing report.
- It deliberately fails closed if the restricted engine, catalog installer, or rollback function is not exposed under the expected API. In that case, inspect the local module API and adapt this helper before using it; do not bypass the restricted engine.
- This is a CLI helper, not yet an n8n HTTP tool. The current service's `/use-tool` and `/receive-code` routes remain untouched.
