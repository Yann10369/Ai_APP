"""model.inference 推理入口: PhotoClassifier / ClassifierConfig / 阿里云客户端。"""
from model.inference.classifier import PhotoClassifier
from model.inference.config import ClassifierConfig
from model.inference.aliyun import AliyunVisionClient

__all__ = [
    "PhotoClassifier",
    "ClassifierConfig",
    "AliyunVisionClient",
]
