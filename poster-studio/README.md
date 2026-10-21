# IBRA Poster Studio prototype

A small HTTP service for composing complete 1920x1920 PNG advertising posters from a source image URL and verified business copy. It is designed for n8n and does not require Canva.

## Planned API
- `GET /health`
- `POST /render`: receives a source image URL, business name, headline, optional price/currency, offer, phone/address and category. Returns a direct `image_url`, never Base64.

## Requirements before deployment
- Restrict source image URLs to the IBRA Cloudflare image gateway to prevent SSRF.
- Use `POSTER_API_KEY` for API authentication.
- Persist rendered files to object storage such as Cloudflare R2; ephemeral filesystems can be cleared on redeploy.
- Benchmark actual quality and latency for restaurant, fashion and luxury posters before commercial use.

This is a prototype plan, not yet a deployed service.