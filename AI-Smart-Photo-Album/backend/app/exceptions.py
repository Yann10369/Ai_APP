"""业务异常：携带 (code, message)，由 main.py 的 handler 转 JSONResponse。"""


class BizException(Exception):
    """业务异常，携带 HTTP 状态码与错误消息。"""
    def __init__(self, code: int, message: str):
        self.code = code
        self.message = message
        super().__init__(message)
