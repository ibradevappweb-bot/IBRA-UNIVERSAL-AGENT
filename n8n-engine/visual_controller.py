"""IBRA visual quality controller for generated images.

Uses Cloudflare Workers AI vision to compare a generated image with its request.
This module is intentionally standalone until the local image route is integrated.
No image is regenerated here; it returns a bounded correction instruction.
"""
import base64
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path

MODEL = os.environ.get(
    "IBRA_VISUAL_MODEL", "@cf/meta/llama-3.2-11b-vision-instruct"
)
MAX_IMAGE_BYTES = 8 * 1024 * 1024
TIMEOUT_SECONDS = 60

MIME_BY_SUFFIX = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}

SYSTEM_PROMPT = """Tu es le contrôleur qualité visuelle d'IBRA Evolution.
Inspecte l'image fournie et compare-la au prompt demandé.
Vérifie uniquement ce qui est visible : respect du sujet et de la scène,
objets/personnes manquants ou ajoutés, anatomie visible, mains/visages,
membres coupés involontairement, doublons, texte illisible et défauts majeurs.
Ne prétends pas détecter avec certitude ce qui n'est pas clairement visible.
Réponds uniquement en JSON valide avec ces champs :
{
  "pass": boolean,
  "score": integer de 0 à 100,
  "issues": [courtes descriptions en français],
  "correction_prompt": "instruction concise de modification en français"
}
Si l'image respecte globalement le prompt et n'a pas de défaut majeur,
pass=true, issues=[], correction_prompt="".
Si une correction est nécessaire, pass=false et donne une instruction précise.
Ne demande jamais de changer le modèle d'image.
"""

def _load_image(path):
    image_path = Path(path)
    if not image_path.is_file():
        raise ValueError("Fichier image introuvable.")
    mime = MIME_BY_SUFFIX.get(image_path.suffix.lower())
    if not mime:
        raise ValueError("Format image accepté : JPG, PNG ou WEBP.")
    raw = image_path.read_bytes()
    if not raw:
        raise ValueError("Le fichier image est vide.")
    if len(raw) > MAX_IMAGE_BYTES:
        raise ValueError("Image trop volumineuse (maximum 8 Mo).")
    return mime, base64.b64encode(raw).decode("ascii")

def _credentials():
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    token = (
        os.environ.get("CLOUDFLARE_AUTH_TOKEN", "").strip()
        or os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    )
    if not account_id or not token:
        raise RuntimeError(
            "Configuration manquante : CLOUDFLARE_ACCOUNT_ID et "
            "CLOUDFLARE_AUTH_TOKEN (ou CLOUDFLARE_API_TOKEN)."
        )
    return account_id, token

def _extract_text(payload):
    result = payload.get("result", {})
    if isinstance(result, dict):
        for key in ("response", "text", "output"):
            if isinstance(result.get(key), str):
                return result[key]
    if isinstance(result, str):
        return result
    raise ValueError("Réponse du modèle vision non reconnue.")

def _parse_review(text):
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("Le modèle vision n'a pas retourné un JSON valide.")
    review = json.loads(cleaned[start:end + 1])
    if type(review.get("pass")) is not bool:
        raise ValueError("Champ 'pass' absent ou invalide.")
    score = review.get("score")
    if type(score) is not int or not 0 <= score <= 100:
        raise ValueError("Score visuel invalide (entier de 0 à 100 attendu).")
    issues = review.get("issues", [])
    correction = review.get("correction_prompt", "")
    if not isinstance(issues, list) or any(not isinstance(x, str) for x in issues):
        raise ValueError("Liste issues invalide.")
    if not isinstance(correction, str):
        raise ValueError("Instruction de correction invalide.")
    if review["pass"]:
        correction = ""
    else:
        correction = correction.strip()[:2000]
    return {
        "ok": True,
        "pass": review["pass"],
        "score": score,
        "issues": issues[:12],
        "correction_prompt": correction,
        "model": MODEL,
    }

def inspect_image(image_path, requested_prompt, *, opener=None):
    """Return a validated visual review. opener is injectable for unit tests."""
    if not isinstance(requested_prompt, str) or not requested_prompt.strip():
        raise ValueError("Le prompt original est obligatoire.")
    if len(requested_prompt) > 12000:
        raise ValueError("Le prompt original dépasse 12 000 caractères.")
    mime, encoded = _load_image(image_path)
    account_id, token = _credentials()
    endpoint = (
        "https://api.cloudflare.com/client/v4/accounts/"
        + account_id + "/ai/run/" + MODEL
    )
    body = {
        "prompt": SYSTEM_PROMPT + "\n\nPROMPT ORIGINAL :\n" + requested_prompt.strip(),
        "image": "data:" + mime + ";base64," + encoded,
        "max_tokens": 700,
        "temperature": 0.1,
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    open_request = opener or urllib.request.urlopen
    try:
        with open_request(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read(1200).decode("utf-8", "replace")
        raise RuntimeError("Erreur Cloudflare Vision HTTP " + str(exc.code) + ": " + detail) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError("Connexion Cloudflare Vision impossible : " + str(exc.reason)) from exc
    if not payload.get("success", True):
        raise RuntimeError("Cloudflare Vision a refusé la requête.")
    return _parse_review(_extract_text(payload))

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Contrôle qualité visuelle IBRA")
    parser.add_argument("image", help="Chemin de l'image générée")
    parser.add_argument("--prompt", required=True, help="Prompt original de génération")
    args = parser.parse_args()
    print(json.dumps(inspect_image(args.image, args.prompt), ensure_ascii=False, indent=2))
