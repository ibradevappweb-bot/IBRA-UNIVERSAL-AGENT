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
