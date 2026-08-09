import os
import requests
from huggingface_hub import HfApi

EP = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
api = HfApi()

# 候选：fer-2013 系列 + emotion + facial-emotion 系列
candidates = [
    "clip-benchmark/wds_fer2013",
    "Piro17/dataset-affecthqnet-fer2013",
    "echodpp/fer2013_preprocess",
    "UniqueData/facial-emotion-recognition-dataset",
    "manojdilz/facial_emotion_detection_dataset",
    "dima806/facial_emotions_image_detection",
    "trainerX/emotion-recognition-data",
    "mahmoudimaou/fer-2013-image-data",
    "OttoYu/Facial-Expression",
    "KajetanHrynowski/emotion-recognition",
    "areeshaa123/facialemotionrecognition",
    "ScullyowesHenry/facial_emotion_detection_dataset",
    "Piro17/balancednumber-affecthqnet-fer2013",
]

print(f"Using endpoint: {EP}\n")
for did in candidates:
    try:
        info = api.dataset_info(did)
        files = [s.rfilename for s in info.siblings]
        # 筛选含图片或 parquet 的
        img_files = [f for f in files if any(
            f.endswith(ext) for ext in ('.parquet', '.jpg', '.jpeg', '.png', '.webp')
        )]
        has_loader = any(f.endswith('.py') for f in files)
        print(f"✓ {did}")
        print(f"   files: {len(files)} total, {len(img_files)} img/parquet, loader={has_loader}")
        if img_files[:3]:
            print(f"   samples: {img_files[:3]}")
    except Exception as e:
        print(f"✗ {did}: {str(e)[:80]}")