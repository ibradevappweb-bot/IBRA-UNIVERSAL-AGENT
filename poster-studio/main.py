import io, os, re, uuid, time, ipaddress, socket
from urllib.parse import urlparse
from pathlib import Path
from typing import Optional
import requests
from fastapi import FastAPI, HTTPException, Header
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, HttpUrl
from PIL import Image, ImageDraw, ImageFont, ImageFilter

APP_NAME = "IBRA Poster Studio"
OUT = Path(os.getenv("POSTER_OUTPUT_DIR", "/tmp/ibra-posters"))
OUT.mkdir(parents=True, exist_ok=True)
BASE_URL = os.getenv("PUBLIC_BASE_URL", "").rstrip("/")
API_KEY = os.getenv("POSTER_API_KEY", "")
ALLOWED_HOST = os.getenv("SOURCE_IMAGE_HOST", "ibra-image-gateway.ibradevappweb.workers.dev")
SIZE = 1920
app = FastAPI(title=APP_NAME, version="0.1.0")
app.mount("/posters", StaticFiles(directory=str(OUT)), name="posters")

class PosterRequest(BaseModel):
    image_url: HttpUrl
    business_name: str = Field(min_length=1, max_length=90)
    headline: str = Field(min_length=1, max_length=100)
    price: Optional[str] = Field(default=None, max_length=50)
    offer: Optional[str] = Field(default=None, max_length=120)
    phone: Optional[str] = Field(default=None, max_length=50)
    address: Optional[str] = Field(default=None, max_length=100)
    category: str = Field(default="restaurant", pattern="^(restaurant|fashion|luxury|general)$")
    currency: Optional[str] = Field(default=None, max_length=12)
    accent_color: Optional[str] = Field(default=None, pattern="^#[0-9A-Fa-f]{6}$")
    cta: Optional[str] = Field(default="Commandez maintenant", max_length=50)

def safe_public_url(url: str):
    p = urlparse(url)
    if p.scheme != "https" or p.hostname != ALLOWED_HOST or not p.path.startswith("/image/"):
        raise HTTPException(400, "image_url doit provenir de la passerelle image IBRA autorisée")

def load_font(size, bold=False):
    paths = (["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf"] if bold else
             ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"])
    for path in paths:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()

def fit_text(draw, text, max_width, initial_size, bold=True, min_size=28):
    size = initial_size
    while size > min_size:
        font = load_font(size, bold)
        if draw.textbbox((0,0), text, font=font)[2] <= max_width:
            return font
        size -= 2
    return load_font(min_size, bold)

def wrap_lines(draw, text, font, max_width):
    words = (text or "").split()
    lines, current = [], ""
    for word in words:
        candidate = (current + " " + word).strip()
        if draw.textbbox((0,0), candidate, font=font)[2] <= max_width:
            current = candidate
        else:
            if current: lines.append(current)
            current = word
    if current: lines.append(current)
    return lines[:3]

def hex_rgb(s, fallback):
    if not s: return fallback
    return tuple(int(s[i:i+2],16) for i in (1,3,5))

@app.get("/health")
def health():
    return {"ok": True, "service": "ibra-poster-studio", "version": "0.1.0", "output": "1920x1920 PNG"}

@app.post("/render")
def render(req: PosterRequest, x_api_key: Optional[str] = Header(default=None)):
    started = time.time()
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(401, "Clé API invalide")
    url = str(req.image_url)
    safe_public_url(url)
    try:
        response = requests.get(url, timeout=(5, 25), stream=True, allow_redirects=False,
                                headers={"User-Agent":"IBRA-Poster-Studio/0.1"})
        response.raise_for_status()
        content = response.raw.read(15 * 1024 * 1024 + 1)
        if len(content) > 15 * 1024 * 1024:
            raise HTTPException(413, "Image source supérieure à 15 Mo")
        src = Image.open(io.BytesIO(content)).convert("RGB")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(422, f"Impossible de charger l'image source: {type(e).__name__}")
    # Crop to a square; source visual is preserved as the main subject.
    w,h = src.size
    side = min(w,h)
    left, top = (w-side)//2, (h-side)//2
    src = src.crop((left,top,left+side,top+side)).resize((SIZE,SIZE), Image.Resampling.LANCZOS)
    canvas = src.convert("RGBA")
    # Dark bottom gradient provides reliable contrast for typography.
    overlay = Image.new("RGBA", (1, SIZE-650), (7, 9, 13, 0))
    pix = overlay.load()
    for y in range(SIZE-650):
        alpha = int(205 * (y/(SIZE-650))**0.8)
        pix[0,y] = (7, 9, 13, alpha)
    overlay = overlay.resize((SIZE, SIZE-650))
    full_overlay = Image.new("RGBA", (SIZE,SIZE), (0,0,0,0))
    full_overlay.paste(overlay, (0,650))
    canvas = Image.alpha_composite(canvas, full_overlay)
    draw = ImageDraw.Draw(canvas)
    accent = hex_rgb(req.accent_color, {"restaurant":(228,151,63),"fashion":(239,239,232),"luxury":(207,177,111),"general":(255,194,66)}[req.category])
    margin = 112
    # Top brand plaque
    brand_font = fit_text(draw, req.business_name.upper(), 1280, 47, True, 30)
    draw.rounded_rectangle((margin,margin, min(SIZE-margin, margin+1400), margin+100),
                           radius=26, fill=(9,10,12,165), outline=accent+(210,), width=3)
    draw.text((margin+30,margin+23), req.business_name.upper(), font=brand_font, fill=(255,255,255,255))
    # Editorial bottom layout
    y = 1190
    if req.offer:
        offer_font = fit_text(draw, req.offer.upper(), 1550, 48, True, 30)
        draw.rounded_rectangle((margin,y, min(SIZE-margin,margin+1550), y+82), radius=18, fill=accent+(255,))
        draw.text((margin+24,y+14), req.offer.upper(), font=offer_font, fill=(15,15,15,255))
        y += 115
    headline_font = fit_text(draw, req.headline, 1660, 105 if req.category != "luxury" else 92, True, 48)
    lines = wrap_lines(draw, req.headline, headline_font, 1660)
    for line in lines:
        draw.text((margin,y), line, font=headline_font, fill=(255,255,255,255),
                  stroke_width=1, stroke_fill=(0,0,0,120))
        y += headline_font.size + 18
    y += 12
    if req.price:
        price_text = req.price + ((" " + req.currency) if req.currency else "")
        price_font = fit_text(draw, price_text, 920, 86, True, 42)
        box_w = min(1050, draw.textbbox((0,0),price_text,font=price_font)[2]+88)
        draw.rounded_rectangle((margin,y,margin+box_w,y+132), radius=28, fill=accent+(255,))
        draw.text((margin+34,y+17), price_text, font=price_font, fill=(13,13,13,255))
        y += 160
    details = [x for x in [req.cta, req.phone, req.address] if x]
    detail_font = load_font(34, False)
    detail_y = min(y+4, SIZE-230)
    if details:
        # A small bullet separator keeps footer tidy without faking a logo.
        text = "  •  ".join(details)
        max_w = SIZE-2*margin
        f = fit_text(draw, text, max_w, 35, False, 23)
        draw.text((margin,detail_y), text, font=f, fill=(255,255,255,255))
    # Narrow accent baseline
    draw.rounded_rectangle((margin,SIZE-95,SIZE-margin,SIZE-82), radius=6, fill=accent+(255,))
    filename = f"{uuid.uuid4().hex}.png"
    path = OUT / filename
    canvas.convert("RGB").save(path, "PNG", optimize=True)
    base = BASE_URL or ""
    return {"success":True,"image_url":f"{base}/posters/{filename}","width":SIZE,"height":SIZE,
            "format":"image/png","render_ms":round((time.time()-started)*1000),"file":filename}

