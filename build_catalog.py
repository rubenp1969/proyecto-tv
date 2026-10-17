#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agregador Ultrarrápido de Catálogo Híbrido (TV en Vivo + VOD)
Optimizado para bajo consumo de datos y ejecución en menos de 15 segundos.
"""

import gzip
import hashlib
import json
import os
import re
import time
import urllib.request
import urllib.error
import uuid

WORKER_BASE_URL = os.environ.get("WORKER_BASE_URL", "https://tv-stitcher.tu-subdominio.workers.dev")
CATALOG_BASE_URL = os.environ.get("CATALOG_BASE_URL", "https://tu-usuario.github.io/catalogo-tv")
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist")

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
    "Accept": "application/json, text/plain, */*",
}

PLUTO_REGIONS = {
    "LATAM": {"ip": "187.188.0.1", "code": "latam"},
    "ES": {"ip": "81.33.0.1", "code": "es"},
    "US-ES": {"ip": "66.249.64.1", "code": "us"},
}

M3U_SOURCES = [
    {"origin": "LATAM", "url": "https://iptv-org.github.io/iptv/languages/spa.m3u"},
    {"origin": "VE", "url": "https://iptv-org.github.io/iptv/countries/ve.m3u"},
    {"origin": "ES", "url": "https://www.tdtchannels.com/lists/tv.m3u8"},
]

CATEGORY_MAP = {
    "Cine": ["cine", "movie", "película", "pelicula", "film", "acción", "accion", "drama", "terror", "scifi", "runtime", "rakuten", "canela"],
    "Deportes": ["deporte", "sport", "fútbol", "futbol", "motor", "boxeo", "fifa", "realmadrid"],
    "Noticias": ["noticia", "news", "24h", "dw", "france 24", "telesur", "globovisión", "globovision"],
    "Infantil": ["infantil", "kids", "niños", "ninos", "anime", "nick", "junior", "clan", "boing"],
    "Series y Novelas": ["serie", "novela", "telenovela", "retro", "investigación", "crimen"],
    "Música y Variedades": ["música", "musica", "music", "mtv", "cocina", "viaje", "documental"],
}

def fetch_url(url, extra_headers=None, timeout=6):
    headers = dict(DEFAULT_HEADERS)
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            if resp.info().get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            return raw.decode("utf-8", errors="replace")
    except Exception:
        return None

def normalize_category(raw_group, title=""):
    combined = f"{raw_group} {title}".lower()
    for clean_cat, keywords in CATEGORY_MAP.items():
        if any(kw in combined for kw in keywords):
            return clean_cat
    return "Entretenimiento"

def extract_pluto():
    live, movies, series = [], [], []
    seen = set()

    for origin, cfg in PLUTO_REGIONS.items():
        print(f"  -> Conectando a Pluto TV ({origin})...")
        cid = str(uuid.uuid4())
        boot = fetch_url(
            f"https://boot.pluto.tv/v4/start?appName=web&appVersion=8.0.0&clientID={cid}",
            extra_headers={"X-Forwarded-For": cfg["ip"]}
        )
        if not boot:
            continue
        try:
            bdata = json.loads(boot)
            token = bdata.get("sessionToken", "")
            if not token:
                continue
            headers = {"Authorization": f"Bearer {token}", "X-Forwarded-For": cfg["ip"]}

            # Canales en vivo
            ch_raw = fetch_url("https://service-channels.clusters.pluto.tv/v2/guide/channels?offset=0&limit=500", headers)
            if ch_raw:
                for ch in json.loads(ch_raw).get("data", []):
                    ch_id = ch.get("id") or ch.get("_id")
                    name = ch.get("name", "").strip()
                    if ch_id and name and ch_id not in seen:
                        seen.add(ch_id)
                        logo = next((i["url"] for i in ch.get("images", []) if "logo" in i.get("type", "").lower()), "")
                        live.append({
                            "id": f"pluto-{ch_id}",
                            "name": name,
                            "cat": normalize_category(ch.get("category", ""), name),
                            "origin": origin,
                            "logo": logo,
                            "url": f"{WORKER_BASE_URL}/play?id={ch_id}&reg={cfg['code']}"
                        })

            # Películas y Series VOD (sin bucle de episodios para máxima velocidad)
            vod_raw = fetch_url("https://service-vod.clusters.pluto.tv/v4/vod/categories?includeItems=true&deviceType=web&offset=0&limit=30", headers)
            if vod_raw:
                for cat in json.loads(vod_raw).get("categories", []):
                    for item in cat.get("items", []):
                        v_id = item.get("_id") or item.get("id")
                        title = item.get("name", "").strip()
                        if not v_id or not title or v_id in seen:
                            continue
                        seen.add(v_id)
                        poster = item.get("covers", [{}])[0].get("url", "")
                        if item.get("type") == "movie":
                            movies.append({
                                "id": f"mov-{v_id}",
                                "title": title,
                                "cat": normalize_category(item.get("genre", ""), title),
                                "origin": origin,
                                "poster": poster,
                                "url": f"{WORKER_BASE_URL}/vod?id={v_id}&reg={cfg['code']}"
                            })
                        else:
                            series.append({
                                "id": f"ser-{v_id}",
                                "title": title,
                                "cat": normalize_category(item.get("genre", ""), title),
                                "origin": origin,
                                "poster": poster,
                                "detail_url": f"{CATALOG_BASE_URL}/series/ser-{v_id}.json"
                            })
        except Exception:
            continue
    return live, movies, series

def parse_m3u():
    channels = []
    seen = set()
    regex = re.compile(r'#EXTINF:-?\d+\s+(.*?),\s*(.+)$')
    attr = re.compile(r'([\w-]+)="([^"]*)"')

    for src in M3U_SOURCES:
        print(f"  -> Descargando lista {src['origin']}...")
        raw = fetch_url(src["url"], timeout=8)
        if not raw:
            continue
        cur = None
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("#EXTINF:"):
                m = regex.match(line)
                if m:
                    attrs = dict(attr.findall(m.group(1)))
                    cur = {"name": m.group(2).strip(), "logo": attrs.get("tvg-logo", ""), "group": attrs.get("group-title", ""), "country": src["origin"]}
            elif not line.startswith("#") and cur and ".m3u8" in line.lower():
                if line not in seen:
                    seen.add(line)
                    channels.append({
                        "id": f"hls-{hashlib.md5(line.encode()).hexdigest()[:8]}",
                        "name": cur["name"],
                        "cat": normalize_category(cur["group"], cur["name"]),
                        "origin": src["origin"],
                        "logo": cur["logo"],
                        "url": line
                    })
                cur = None
    return channels

def save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = json.dumps(data, separators=(",", ":")).encode("utf-8")
    with open(path, "wb") as f:
        f.write(raw)
    with gzip.open(f"{path}.gz", "wb") as gz:
        gz.write(raw)

def main():
    print("[1/3] Extrayendo canales y VOD de Pluto TV...")
    p_live, movies, series = extract_pluto()

    print("[2/3] Extrayendo listas M3U abiertas (TDTChannels, IPTV-org)...")
    m_live = parse_m3u()

    all_live = p_live + m_live
    print(f"[3/3] Guardando {len(all_live)} canales, {len(movies)} películas y {len(series)} series...")

    save(os.path.join(OUTPUT_DIR, "live_es.json"), {"channels": all_live})
    save(os.path.join(OUTPUT_DIR, "vod_movies_es.json"), {"movies": movies})
    save(os.path.join(OUTPUT_DIR, "vod_series_es.json"), {"series": series})
    save(os.path.join(OUTPUT_DIR, "manifest.json"), {"updated": int(time.time()), "channels_count": len(all_live)})

    print("¡Listo! Catálogo generado en la carpeta dist/")

if __name__ == "__main__":
    main()
