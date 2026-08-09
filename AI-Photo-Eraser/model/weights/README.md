# 权重目录

放置模型权重文件。建议放到这里：

- `lama.onnx` —— LaMa 导出后的 ONNX 模型
- `big-lama.safetensors` —— 原始 PyTorch 权重（备份用）

## LaMa 权重获取

```bash
# 1. 克隆 LaMa 官方仓库
git clone https://github.com/advimman/lama.git
cd lama

# 2. 下载预训练权重
wget https://huggingface.co/smartywu/big-lama/resolve/main/big-lama.pt -P checkpoints/

# 3. 导出 ONNX
python scripts/export_onnx.py \
    --checkpoint checkpoints/big-lama.pt \
    --output-path ./lama.onnx
```

## GPU / CPU 依赖

```bash
# CPU
pip install onnxruntime

# GPU（CUDA 11.8+）
pip install onnxruntime-gpu
```

## 注意

- 该目录应加入 `.gitignore`，不要提交到仓库
- 生产环境建议从 OSS / S3 下载权重，避免污染代码仓库