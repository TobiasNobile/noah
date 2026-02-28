from pathlib import Path
import requests
import time

ROOT_DIR   = Path(__file__).resolve().parent.parent.parent
IMAGES_DIR = ROOT_DIR / "assets" / "images"

PEXELS_KEY = "v8Fizby7LZBNOKOonMweMB1ANs93Rwed0DQ6oNP2gWl26YDw1YXvTnqq"
HEADERS    = {"Authorization": PEXELS_KEY}

queries = [
    ("paris metro station interior",  "environnement"),
    ("underground subway corridor",   "environnement"),
    ("train platform crowd",          "environnement"),
    ("subway exit sign",              "document"),
    ("transport ticket",              "document"),
    ("metro map sign",                "document"),
]

PER_QUERY = 9  # 6 × 9 = 54 images

for query, folder in queries:
    out = IMAGES_DIR / folder
    out.mkdir(parents=True, exist_ok=True)

    resp = requests.get(
        "https://api.pexels.com/v1/search",
        params={"query": query, "per_page": PER_QUERY},
        headers=HEADERS
    )

    photos = resp.json().get("photos", [])
    if not photos:
        print(f"⚠️  Aucun résultat pour '{query}'")
        continue

    for i, photo in enumerate(photos):
        url      = photo["src"]["large"]
        img_data = requests.get(url).content
        path     = out / f"{query.replace(' ', '_')}_{i}.jpg"
        path.write_bytes(img_data)
        time.sleep(0.1)

    print(f"✅ {len(photos):2d} images — {folder:15s} — '{query}'")