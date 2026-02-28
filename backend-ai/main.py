import cv2
import numpy as np

def laplacian_variance(img_gray):
    return cv2.Laplacian(img_gray, cv2.CV_64F).var()

def edge_density(img_gray):
    edges = cv2.Canny(img_gray, 100, 200)
    return np.sum(edges > 0) / edges.size

def text_blob_ratio(img_gray):
    mser = cv2.MSER_create()
    regions, _ = mser.detectRegions(img_gray)
    if not regions:
        return 0
    ratios = []
    for region in regions:
        x, y, w, h = cv2.boundingRect(region.reshape(-1, 1, 2))
        if h > 0:
            ratios.append(w / h)
    return np.mean(ratios) if ratios else 0

def classify_image(image_path):
    img = cv2.imread(image_path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    lap_var = laplacian_variance(gray)
    edge_d = edge_density(gray)

    # Règles simples à ajuster selon tes données
    score = 0
    if lap_var > 500:
        score += 1
    if edge_d > 0.12:
        score += 1

    return "DOCUMENT" if score >= 2 else "ENVIRONNEMENT"
