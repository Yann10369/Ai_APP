"""全局配置"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # 后端选择
    default_backend: str = "lama"

    # LaMa
    lama_model_path: str = "./weights/lama.onnx"
    lama_provider: str = "cpu"

    # Stable Diffusion
    sd_endpoint: str = ""
    sd_api_key: str = ""
    sd_model: str = "stabilityai/stable-diffusion-2-inpainting"

    # 豆包
    doubao_api_key: str = ""
    doubao_model: str = ""
    doubao_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"

    # 自动蒙版
    vision_provider: str = ""
    vision_api_key: str = ""
    vision_model: str = "gpt-4o-mini"

    # 缓存
    redis_url: str = ""
    cache_ttl: int = 86400

    # 评估 & 日志
    eval_dataset: str = "tests/fixtures/eval_set.json"
    log_dir: str = "./logs"


settings = Settings()