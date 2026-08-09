from PIL import Image
import os
from collections import Counter

img_dir = r"D:\college.study\Project\AI-Smart-Photo-Album\docs\image_data"
files = sorted([f for f in os.listdir(img_dir) if f.endswith('.jpg')])

# 情绪分布
labels = []
for f in files:
    name = f.rsplit('.', 1)[0]
    parts = name.split('_', 1)
    if len(parts) == 2:
        labels.append(parts[1])

print(f"Total: {len(files)} files")
print(f"Labels distribution: {dict(Counter(labels))}")
print()
print("First 3 image details:")
for f in files[:3]:
    img = Image.open(os.path.join(img_dir, f))
    print(f"  {f}: size={img.size}, mode={img.mode}, bytes={os.path.getsize(os.path.join(img_dir, f))}")