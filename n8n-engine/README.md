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
The engine uses a restricted Python AST interpreter. Imports, attribute access, loops, classes and arbitrary function calls are rejected. This is not a complete OS sandbox. Keep the service local and require TUTEUR review before approving tools.


## Autonomous evolution cycle (proposal-only mode)

`evolution.py` now implements one bounded cycle:
1. choose the highest-priority unfinished backlog item deterministically;
2. optionally query Exa for real research sources when `EXA_API_KEY` is set;
3. ask Gemini to draft a small `run(data)` helper and 3–5 test cases;
4. execute the candidate tests using the restricted interpreter in `engine.py`;
5. ask Gemini in a separate request to act as the independent TUTEUR;
6. save the source list, candidate, individual test outcomes, review, and status in `evolution-data/proposals/`.

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

A proposal is **not automatically installed** in the live engine. It is saved with status `READY_FOR_REVIEW` only if every candidate test passes and the TUTEUR approves it; otherwise it is `REJECTED`. This first version deliberately does not rewrite the engine, commit to GitHub, merge branches, or promote generated code. That restriction protects the existing Android application and avoids treating a model's approval as proof of safety.

The GitHub Actions test workflow validates the Python code and deterministic mock-based cycle tests. It does not prove that external Gemini/Exa credentials work, nor that the 48-hour n8n schedule is installed or active. The n8n JSON is an import template; replace its placeholder path with the actual Python path on the n8n host, configure credentials securely, test a manual run, then activate the schedule. Do not expose the local engine on the public Internet without authentication and a stronger sandbox.
