"""自定义异常"""


class InpaintError(Exception):
    """所有擦除相关异常的基类"""


class BackendUnavailableError(InpaintError):
    """后端不可用（权重缺失 / API Key 未配置）"""


class InvalidMaskError(InpaintError):
    """蒙版无效（尺寸不对 / 全黑）"""


class SizeMismatchError(InpaintError):
    """原图与蒙版尺寸不一致"""


class ModelTimeoutError(InpaintError):
    """推理超时"""