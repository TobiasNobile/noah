import cv2
import numpy as np

def laplacian_variance(img_gray):
    return cv2.Laplacian(img_gray, cv2.CV_64F).var()




