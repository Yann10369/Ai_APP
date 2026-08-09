import os
print("HF_ENDPOINT:", os.environ.get("HF_ENDPOINT", "NOT SET"))
print("HF_HOME:", os.environ.get("HF_HOME", "NOT SET"))
print("HF_HUB_OFFLINE:", os.environ.get("HF_HUB_OFFLINE", "NOT SET"))

import requests
ep = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
print(f"Testing endpoint: {ep}")
try:
    r = requests.get(f"{ep}/api/datasets/Jeneral/fer2013", timeout=15)
    print(f"Status: {r.status_code}")
    if r.status_code == 200:
        data = r.json()
        print(f"Dataset id: {data.get('id')}")
        print(f"Siblings (files):")
        for s in data.get('siblings', [])[:15]:
            print(f"  {s.get('rfilename')}")
except Exception as e:
    print(f"Error: {e}")