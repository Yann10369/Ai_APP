from huggingface_hub import HfApi
api = HfApi()
queries = ["fer-2013", "emotion", "facial-emotion"]
for q in queries:
    print(f"=== {q} ===")
    results = list(api.list_datasets(search=q, limit=8))
    for r in results:
        print(f"  {r.id}")