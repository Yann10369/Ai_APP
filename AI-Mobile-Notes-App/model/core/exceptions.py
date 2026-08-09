"""自定义异常体系"""


class ModelError(Exception):
    """所有模型相关异常的基类"""


class AuthError(ModelError):
    """鉴权失败（401）"""


class RateLimitError(ModelError):
    """触发限流（429）"""


class ModelTimeoutError(ModelError):
    """调用超时"""


class OutputParseError(ModelError):
    """模型输出无法解析为预期结构"""


class QuotaExceededError(ModelError):
    """配额耗尽"""