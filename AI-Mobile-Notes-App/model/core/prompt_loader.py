"""Prompt 加载器：从 YAML 读取，使用 Jinja2 渲染"""
from pathlib import Path
from typing import Dict, Optional

import yaml
from jinja2 import Environment


class PromptLoader:
    def __init__(self, prompts_dir: str = "prompts"):
        self.env = Environment(trim_blocks=True, lstrip_blocks=True)
        self.prompts: Dict[str, dict] = {}
        for f in Path(prompts_dir).glob("*.yaml"):
            data = yaml.safe_load(f.read_text(encoding="utf-8"))
            if not data or "name" not in data:
                continue
            self.prompts[data["name"]] = data

    def get(self, name: str) -> Optional[dict]:
        return self.prompts.get(name)

    def render(self, name: str, variables: dict) -> tuple[str, str, dict]:
        """返回 (system, user, meta)"""
        p = self.prompts.get(name)
        if not p:
            raise KeyError(f"Prompt not found: {name}")
        sys_tmpl = self.env.from_string(p.get("system", ""))
        user_tmpl = self.env.from_string(p.get("user", ""))
        meta = {k: v for k, v in p.items() if k not in ("system", "user")}
        return (
            sys_tmpl.render(**variables),
            user_tmpl.render(**variables),
            meta,
        )