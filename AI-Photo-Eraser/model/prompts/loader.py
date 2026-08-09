"""Prompt 加载器"""
from pathlib import Path
from typing import Optional


class PromptLoader:
    """支持 YAML 场景化 Prompt 加载
    YAML 结构示例：
        name: sd_inpaint
        version: "1.0"
        scenes:
          person: "..."
          pet:    "..."
          ...
    """
    def __init__(self, prompts_dir: str = "model/prompts"):
        self.data = {}
        for f in Path(prompts_dir).glob("*.yaml"):
            try:
                import yaml
                d = yaml.safe_load(f.read_text(encoding="utf-8"))
                if d and "name" in d:
                    self.data[d["name"]] = d
            except Exception:
                continue

    def render(self, name: str, variables: dict) -> Optional[str]:
        d = self.data.get(name)
        if not d:
            return None
        scene = variables.get("scene", "object")
        text = d.get("scenes", {}).get(scene, "")
        return text.strip() if text else None