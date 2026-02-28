import cv2
import numpy as np

def laplacian_variance(img_gray):
    return cv2.Laplacian(img_gray, cv2.CV_64F).var()

def edge_density(img_gray):
    edges = cv2.Canny(img_gray, 100, 200)
    return np.sum(edges > 0) / edges.size


