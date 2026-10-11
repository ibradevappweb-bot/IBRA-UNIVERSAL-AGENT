# IBRA Python Engine for n8n

This branch adds a Python tool engine under n8n-engine/. The default main branch and Kotlin Android files are unchanged.

## Start
Use Python 3.10 or newer. No third-party packages are required.

Run: python engine.py

The local HTTP service listens on 127.0.0.1 port 8766 by default. Persistent draft and approved tool records are stored in the data directory.

## API
GET /health
GET /drafts
GET /tools
POST /draft
POST /test
POST /promote
POST /run

n8n should send generated tool code to /draft, provide test data to /test, let the TUTEUR review the code and test result, and only then call /promote. Approved tools can be invoked through /run.

Set IBRA_ENGINE_TOKEN to require a bearer token. A token is mandatory if the service listens on a network interface other than localhost.

## Tests
Run: python -m unittest -v

## Safety
The engine uses a restricted Python AST interpreter. Imports, attribute access, while loops, classes and arbitrary function calls are rejected. It supports bounded for loops, one-level list comprehensions, range(), safe text helpers (lower, upper, strip, replace, split, join) and bounded collection helpers (sorted, all, any). Collection operations are limited to 1,000 items, with a 5,000-step execution budget, a 20-level block-depth limit and 300 KB input/output limits. Nested list comprehensions are rejected. This is not a complete OS sandbox. Keep the service local and require TUTEUR review before approving tools.


## Autonomous evolution cycle with versioned tool catalog

`evolution.py` now implements one bounded cycle:
1. choose the highest-priority unfinished backlog item deterministically;
2. optionally query Exa for real research sources when `EXA_API_KEY` is set;
3. ask Gemini to draft a small `run(data)` helper and 3–5 test cases;
4. execute the candidate tests using the restricted interpreter in `engine.py`;
5. ask Gemini in a separate request to act as the independent TUTEUR;
6. save the audit record, then install approved tools into the versioned `tool_catalog.py` catalogue; retain prior versions for rollback.

### Run one cycle

From this directory, with Python 3.10+:

```sh
export GEMINI_API_KEY='configure-this-in-your-secret-manager'
# Optional: enables Exa web research
export EXA_API_KEY='configure-this-in-your-secret-manager'
python -m unittest -v
python evolution.py --status
python evolution.py
```

Do not put keys in source files, commit them, or send them in chat. Gemini is required; without `GEMINI_API_KEY`, the cycle stops with an explicit error. Exa is optional; without its key the cycle records an empty source list and does not claim to have researched the web. Provider availability, model access, quotas and billing depend on the configured accounts.

### Important safety boundary

A generated tool is installed into the separate versioned catalogue only when every candidate test passes and the separate TUTEUR request approves it. Failed tests or a rejected review prevent installation. The engine source, Git history, and Android application are never rewritten by the evolution cycle. The catalogue runs tools through the restricted AST interpreter and preserves previous versions for rollback. All HTTP mutation routes require `IBRA_CATALOG_TOKEN`; without it, POST requests fail closed with HTTP 503. With it, every POST requires a Bearer token. A TUTEUR response is still model-based review, not proof of safety; review and test results must be inspected for high-impact uses.

The GitHub Actions test workflow validates the Python code and deterministic mock-based cycle tests. It does not prove that external Gemini/Exa credentials work, nor that the 48-hour n8n schedule is installed or active. The n8n JSON is an import template; replace its placeholder path with the actual Python path on the n8n host, configure credentials securely, test a manual run, then activate the schedule. Do not expose the local engine on the public Internet without authentication and a stronger sandbox.


## Trigger the cycle from n8n

The local API wrapper is `evolution_service.py`. Start it in the same Python environment/host as n8n:

```sh
export GEMINI_API_KEY='set-this-securely-in-your-shell-or-service-manager'
# Optional real web research:
export EXA_API_KEY='set-this-securely-if-available'
python evolution_service.py
```

It binds to `127.0.0.1:8767` by default. Check `GET http://127.0.0.1:8767/health`; `GET /status` shows the recorded cycles; `POST /run` runs one cycle. The API only listens on loopback by default. Do not set `IBRA_EVOLUTION_HOST` to a public interface; non-loopback binding requires `IBRA_EVOLUTION_TOKEN` but should still be protected by a firewall and TLS reverse proxy.

Import `IBRA-Evolution-48h.n8n.json` into n8n. It has a manual trigger and a 48-hour Schedule Trigger, both calling the local API. Keep the workflow inactive until you have started the service and verified a manual run. Activate it only after the credentials and actual response have been checked. The workflow is a template in GitHub, not yet imported or activated in your running n8n instance.

Important: the cycle now installs approved tools into the separate versioned catalogue, not into the engine source or Android app. External Gemini/Exa calls have not been verified without your configured credentials, and the 48-hour workflow is not yet confirmed active on your phone.
