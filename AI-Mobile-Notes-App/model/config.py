"""
全局配置（pydantic-settings 自动从 .env 加载）
"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # 模型选择
    model_provider: str = "openai"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    openai_base_url: str = "https://api.openai.com/v1"

    # 备用模型
    fallback_provider: str = ""
    doubao_api_key: str = ""
    doubao_model: str = ""
    doubao_base_url: str = ""

    # 第三方
    qwen_api_key: str = ""
    qwen_model: str = "qwen-vl-plus"

    # Embedding
    embed_provider: str = "openai"
    embed_model: str = "text-embedding-3-small"

    # 通用
    request_timeout: int = 60
    max_retries: int = 3
    cache_size: int = 500
    cache_ttl: int = 3600
    log_level: str = "INFO"
    log_dir: str = "./logs"
    prompts_dir: str = "./prompts"


settings = Settings()