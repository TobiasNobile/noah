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

def horizontal_edge_ratio(gray):
    """
    Dans un document, les bords sont majoritairement horizontaux (lignes de texte).
    Dans un environnement, les bords sont dans toutes les directions.
    """
    sobelx = cv2.Sobel(gray, cv2.CV_64F, 1, 0)  # bords verticaux
    sobely = cv2.Sobel(gray, cv2.CV_64F, 0, 1)  # bords horizontaux
    
    h_energy = np.sum(np.abs(sobely))
    v_energy = np.sum(np.abs(sobelx))
    
    # Un document → ratio proche de 1 (équilibré mais régulier)
    # Une scène → ratio très variable
    return h_energy / (v_energy + 1e-6)

def uniformity_score(gray):
    """
    Dans un document, le fond est uniforme (blanc/beige).
    Dans une scène, les pixels sont très variés.
    """
    hist = cv2.calcHist([gray], [0], None, [256], [0, 256])
    hist = hist / hist.sum()
    # Entropie — faible = uniforme (document), élevée = complexe (scène)
    entropy = -np.sum(hist * np.log2(hist + 1e-6))
    return entropy  # Document < 6, Environnement > 6

def classify_image(image_path):
    img = cv2.imread(image_path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    entropy = uniformity_score(gray)
    h_ratio = horizontal_edge_ratio(gray)

    is_document = entropy < 6.0 and (0.8 < h_ratio < 1.2)

    label = "DOCUMENT" if is_document else "ENVIRONNEMENT"
    return {
        "file": image_path.name,
        "label_pred": label,
        "entropy": round(float(entropy), 3),
        "h_edge_ratio": round(float(h_ratio), 3),
    }
