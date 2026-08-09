import os
from datasets import load_dataset

ds = load_dataset(
    "Piro17/dataset-affecthqnet-fer2013",
    split="train",
    streaming=True,
)
print("Peeking first sample...")
sample = next(iter(ds))
print(f"Sample keys: {list(sample.keys())}")
for k, v in sample.items():
    if hasattr(v, 'size'):
        print(f"  {k}: PIL Image, size={v.size}, mode={v.mode}")
    elif isinstance(v, (int, float, str)):
        print(f"  {k}: {type(v).__name__} = {v}")
    elif isinstance(v, (list, tuple)):
        print(f"  {k}: {type(v).__name__} of len {len(v)}, first={v[0] if v else None}")
    else:
        print(f"  {k}: {type(v).__name__}")