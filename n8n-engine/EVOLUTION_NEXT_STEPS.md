# Evolution planner status

The first-stage planner is committed in `evolution.py`. It selects the highest-priority unfinished item and uses an ID tie-breaker so identical state gives a deterministic choice.

This is only the planning stage. It does not yet call Gemini, research the Internet, write new code, or run a 48-hour schedule. Those capabilities must be added and tested before describing IBRA as autonomous.

Run tests from this directory with `python -m unittest -v`. The existing GitHub Actions workflow runs tests on pushes to this branch when files under `n8n-engine/` change.
