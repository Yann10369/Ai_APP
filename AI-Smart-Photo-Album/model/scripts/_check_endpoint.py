import os
from huggingface_hub import constants, configure_http_backend
print('HF_HUB_BASE_URL:', os.environ.get('HF_HUB_BASE_URL'))
print('HF_ENDPOINT:', os.environ.get('HF_ENDPOINT'))
print('default ENDPOINT:', constants.ENDPOINT)
print('default HF_TOKEN_PATH:', constants.HF_TOKEN_PATH)

# Try loading with the mirror endpoint explicitly
from huggingface_hub import HfApi
api = HfApi()
print('api.endpoint:', api.endpoint)

# Try with explicit endpoint
print()
print('Trying with explicit endpoint override...')
api2 = HfApi(endpoint="https://hf-mirror.com")
info = api2.dataset_info("Piro17/dataset-affecthqnet-fer2013")
print('OK, files:', len(info.siblings))