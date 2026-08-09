# Model

数据模型与机器学习模型目录。

用于存放数据结构定义、数据库 Schema、训练好的机器学习 / 深度学习模型权重、数据集说明等。

## 建议子结构

```
model/
├── data_schema/        # 数据结构定义（与后端 schemas 区分，这里更偏向核心实体）
│   ├── user.json
│   └── order.json
├── datasets/           # 训练 / 评估数据集（通常 .gitignore 排除大文件，使用 DVC / LFS 管理）
├── checkpoints/        # 模型权重与检查点
│   ├── v1.0/
│   └── v1.1/
├── notebooks/          # Jupyter Notebook 探索性分析
├── training/           # 训练脚本
│   ├── train.py
│   └── evaluate.py
├── inference/          # 推理 / 服务化脚本
│   └── predict.py
├── configs/            # 训练 / 模型超参配置（YAML / TOML）
└── README.md
```

## 常用技术栈参考

- **传统 ML：** scikit-learn、XGBoost、LightGBM
- **深度学习：** PyTorch、TensorFlow、JAX
- **大模型：** HuggingFace Transformers、vLLM、LlamaIndex
- **实验跟踪：** MLflow、Weights & Biases
- **数据版本：** DVC、Git LFS

## 注意事项

- 大型权重文件（>100MB）请使用 **Git LFS** 或外部存储，不要直接提交到 Git。
- 训练数据如包含敏感信息，请做好脱敏后再入仓。

## 复现训练

```bash
cd model
pip install -r requirements.txt
python training/train.py --config configs/example.yaml
```