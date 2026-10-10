import base64
from collections import Counter
import json
import logging
import math
import os
import re
import tempfile
import traceback
from threading import Lock
import uuid

from PIL import Image
import redis
import requests
import torch
import dashscope
from dashscope import MultiModalConversation
from deepagents import create_deep_agent

from flask import Flask, request, jsonify
from io import BytesIO

from flask.cli import load_dotenv
from langchain_community.chat_models.tongyi import ChatTongyi
from langchain_core.messages.human import HumanMessage
from langchain_core.messages.tool import tool_call
from langchain_core.prompts.chat import ChatPromptTemplate
from langchain_core.tools import tool
from transformers import CLIPModel, CLIPProcessor

# 创建Flask应用实例
app = Flask(__name__)


class SensitiveDataRedactionFilter(logging.Filter):
    """在输出异常堆栈前遮盖常见密钥格式，避免诊断日志意外泄露凭据。"""

    _key_value_pattern = re.compile(
        r"(?i)\b(api[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?|"
        r"authorization|token|secret)(\s*[:=]\s*)(['\"]?)([^'\"\s,;]+)"
    )
    _bearer_pattern = re.compile(r"(?i)\bBearer\s+[^\s,;]+")
    _dashscope_key_pattern = re.compile(r"\bsk-[A-Za-z0-9_-]{12,}\b")
    _aliyun_key_pattern = re.compile(r"\bLTAI[A-Za-z0-9]{12,}\b")

    def __init__(self, known_secrets=None):
        super().__init__()
        self.known_secrets = [value for value in (known_secrets or []) if value]

    def _redact(self, value):
        value = self._key_value_pattern.sub(r"\1\2[REDACTED]", value)
        value = self._bearer_pattern.sub("Bearer [REDACTED]", value)
        value = self._dashscope_key_pattern.sub("[REDACTED_API_KEY]", value)
        value = self._aliyun_key_pattern.sub("[REDACTED_ACCESS_KEY]", value)
        for secret in self.known_secrets:
            value = value.replace(secret, "[REDACTED_SECRET]")
        return value

    def filter(self, record):
        # 先处理普通日志内容，再单独格式化并清理完整异常堆栈。
        try:
            record.msg = self._redact(record.getMessage())
            record.args = ()
        except Exception:
            pass

        if record.exc_info:
            formatted_traceback = "".join(traceback.format_exception(*record.exc_info))
            record.exc_text = self._redact(formatted_traceback)
            record.exc_info = None
        elif record.exc_text:
            record.exc_text = self._redact(record.exc_text)
        return True

# 获取配置信息（从项目根目录下的 .env 文件中读取键值对，并将其加载到当前进程的环境变量（os.environ）中）
load_dotenv()
API_KEY = os.getenv("DASHSCOPE_API_KEY")
DASHSCOPE_HTTP_BASE_URL = os.getenv("DASHSCOPE_HTTP_BASE_URL", "").strip()
DASHSCOPE_IMAGE_MODEL = os.getenv("DASHSCOPE_IMAGE_MODEL", "qwen-image-2.0-pro").strip()
if DASHSCOPE_HTTP_BASE_URL:
    # DashScope SDK 在模块导入时读取环境变量；这里在 .env 加载后显式应用自定义地域/工作空间地址。
    dashscope.base_http_api_url = DASHSCOPE_HTTP_BASE_URL.rstrip("/")

# Flask 默认日志输出到 stderr；INFO 级别会记录处理阶段，exception 会附带完整堆栈。
app.logger.setLevel(logging.INFO)
app.logger.addFilter(SensitiveDataRedactionFilter([API_KEY]))
if DASHSCOPE_HTTP_BASE_URL:
    app.logger.info("DashScope 自定义 HTTP endpoint 已启用")
else:
    app.logger.warning(
        "DASHSCOPE_HTTP_BASE_URL 未配置，当前使用 SDK 默认 endpoint；"
        "请确认它与 API Key 所属地域/工作空间一致"
    )

# Redis 配置。环境变量可以覆盖默认值，默认值与本地 Docker Redis 保持一致。
REDIS_HOST = os.getenv("REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.getenv("REDIS_PORT", "16379"))
REDIS_DATABASE = int(os.getenv("REDIS_DATABASE", "0"))
REDIS_TIMEOUT_SECONDS = float(os.getenv("REDIS_TIMEOUT_SECONDS", "2"))

# 本地 CLIP 模型目录。模型采用懒加载，避免 Flask 启动时立即占用大量内存。
CLIP_MODEL_PATH = os.getenv(
    "CLIP_MODEL_PATH",
    r"C:\Users\QinXun\.cache\modelscope\models\openai-mirror--clip-vit-base-patch16\snapshots\master",
)
CLIP_DEVICE = os.getenv("CLIP_DEVICE", "cpu")
IMAGE_DOWNLOAD_TIMEOUT_SECONDS = float(os.getenv("IMAGE_DOWNLOAD_TIMEOUT_SECONDS", "30"))
IMAGE_MAX_SIZE_BYTES = int(os.getenv("IMAGE_MAX_SIZE_BYTES", str(50 * 1024 * 1024)))

_redis_client = redis.Redis(
    host=REDIS_HOST,
    port=REDIS_PORT,
    db=REDIS_DATABASE,
    socket_connect_timeout=REDIS_TIMEOUT_SECONDS,
    socket_timeout=REDIS_TIMEOUT_SECONDS,
    decode_responses=True,
)
_clip_processor = None
_clip_model = None
_clip_lock = Lock()


def get_clip_components():
    """懒加载本地 CLIP 模型，避免服务启动时阻塞。"""
    global _clip_processor, _clip_model

    if _clip_processor is not None and _clip_model is not None:
        return _clip_processor, _clip_model

    with _clip_lock:
        if _clip_processor is None or _clip_model is None:
            if not os.path.isdir(CLIP_MODEL_PATH):
                raise FileNotFoundError(f"CLIP模型目录不存在: {CLIP_MODEL_PATH}")

            _clip_processor = CLIPProcessor.from_pretrained(
                CLIP_MODEL_PATH,
                local_files_only=True,
            )
            _clip_model = CLIPModel.from_pretrained(
                CLIP_MODEL_PATH,
                local_files_only=True,
            )
            _clip_model.to(CLIP_DEVICE)
            _clip_model.eval()

    return _clip_processor, _clip_model


def download_image_from_oss(oss_url: str) -> bytes:
    """下载 OSS 图片并限制大小，返回图片二进制内容。"""
    if not isinstance(oss_url, str) or not oss_url.startswith(("http://", "https://")):
        raise ValueError("ossUrl 必须是以 http:// 或 https:// 开头的图片地址")

    response = requests.get(
        oss_url,
        timeout=(5, IMAGE_DOWNLOAD_TIMEOUT_SECONDS),
        stream=True,
    )
    response.raise_for_status()

    content_length = response.headers.get("Content-Length")
    if content_length and int(content_length) > IMAGE_MAX_SIZE_BYTES:
        raise ValueError("远程图片大小超过限制")

    chunks = []
    total_size = 0
    for chunk in response.iter_content(chunk_size=1024 * 1024):
        if not chunk:
            continue
        total_size += len(chunk)
        if total_size > IMAGE_MAX_SIZE_BYTES:
            raise ValueError("远程图片大小超过限制")
        chunks.append(chunk)

    image_data = b"".join(chunks)
    if not image_data:
        raise ValueError("远程图片内容为空")

    # 提前校验文件确实是可读取的图片。
    with Image.open(BytesIO(image_data)) as image:
        image.verify()
    return image_data


def generate_image_embedding(image_data: bytes) -> list[float]:
    """使用本地 CLIP 模型生成归一化的 512 维图片向量。"""
    processor, model = get_clip_components()

    with Image.open(BytesIO(image_data)) as image:
        image = image.convert("RGB")

    inputs = processor(images=image, return_tensors="pt")
    inputs = {name: value.to(CLIP_DEVICE) for name, value in inputs.items()}

    with torch.inference_mode():
        # transformers 5.x 的 get_image_features 返回模型输出对象，
        # 这里直接取视觉编码器的 pooler_output，再经过 CLIP 投影层。
        vision_outputs = model.vision_model(pixel_values=inputs["pixel_values"])
        features = model.visual_projection(vision_outputs.pooler_output)
        features = features / features.norm(p=2, dim=-1, keepdim=True).clamp_min(1e-12)

    embedding = features[0].detach().cpu().tolist()
    if len(embedding) != 512:
        raise ValueError(f"CLIP向量维度异常，期望512，实际{len(embedding)}")
    return embedding


def text_similarity(query: str, description: str) -> float:
    """计算中文字符二元组覆盖率，避免短查询被长描述的长度稀释。"""
    if not isinstance(query, str) or not isinstance(description, str):
        return 0.0

    # 去掉空格和标点，仅保留字母、数字及中文字符，并统一英文大小写。
    normalized_query = "".join(char.casefold() for char in query if char.isalnum())
    normalized_description = "".join(
        char.casefold() for char in description if char.isalnum()
    )
    if not normalized_query or not normalized_description:
        return 0.0

    # 完整查询短语出现在描述中时，直接视为完全匹配。
    if normalized_query in normalized_description:
        return 1.0

    # 使用字符二元组处理未分词的中文；单字查询则退化为单字符匹配。
    ngram_size = 2 if len(normalized_query) > 1 else 1
    query_ngrams = Counter(
        normalized_query[index:index + ngram_size]
        for index in range(len(normalized_query) - ngram_size + 1)
    )
    description_ngrams = Counter(
        normalized_description[index:index + ngram_size]
        for index in range(len(normalized_description) - ngram_size + 1)
    )

    # 以查询中的 n-gram 覆盖比例作为 0 到 1 的相似度分数。
    matched_ngrams = sum(
        min(count, description_ngrams[ngram])
        for ngram, count in query_ngrams.items()
    )
    total_ngrams = sum(query_ngrams.values())
    return matched_ngrams / total_ngrams if total_ngrams else 0.0


def cosine_similarity(vector_a, vector_b) -> float:
    """计算两个向量的余弦相似度；向量格式或维度异常时返回 None。"""
    if not isinstance(vector_a, (list, tuple)) or not isinstance(vector_b, (list, tuple)):
        return None
    if not vector_a or len(vector_a) != len(vector_b):
        return None

    try:
        values_a = [float(value) for value in vector_a]
        values_b = [float(value) for value in vector_b]
        if not all(math.isfinite(value) for value in values_a + values_b):
            return None

        dot_product = sum(a * b for a, b in zip(values_a, values_b))
        norm_a = math.sqrt(sum(value * value for value in values_a))
        norm_b = math.sqrt(sum(value * value for value in values_b))
        if norm_a == 0 or norm_b == 0:
            return None

        # 理论范围为 [-1, 1]；夹取浮点误差，保证返回稳定。
        return max(-1.0, min(1.0, dot_product / (norm_a * norm_b)))
    except (TypeError, ValueError, OverflowError):
        return None


def get_user_image_records(user_id: int) -> list[dict]:
    """从现有用户图片集合和 image:{imageId} 缓存中读取图片记录。"""
    image_ids = _redis_client.smembers(f"images:user:{user_id}")
    records = []

    for image_id in image_ids:
        cached_value = _redis_client.get(f"image:{image_id}")
        if not cached_value:
            continue

        try:
            record = json.loads(cached_value)
        except (json.JSONDecodeError, TypeError):
            continue
        if not isinstance(record, dict):
            continue

        # 再核对记录中的用户 ID，避免错误索引导致跨用户结果泄漏。
        record_user_id = record.get("userId")
        if record_user_id is not None and str(record_user_id) != str(user_id):
            continue

        records.append(record)

    return records


def parse_cached_embedding(record: dict):
    """兼容向量以数组或 JSON 字符串形式存储的缓存记录。"""
    embedding = record.get("embedding") or record.get("embedding512")
    if isinstance(embedding, str):
        try:
            embedding = json.loads(embedding)
        except json.JSONDecodeError:
            return None
    return embedding


def save_image_search_record(user_id, oss_url: str, description: str, embedding: list[float]) -> str:
    """将图片描述、向量和用户信息保存到 Redis，并建立简单索引。"""
    image_id = uuid.uuid4().hex
    record = {
        "imageId": image_id,
        "userId": user_id,
        "ossUrl": oss_url,
        "description": description,
        "embedding": embedding,
        "embeddingDim": len(embedding),
    }

    record_key = f"image:{image_id}"
    user_index_key = f"images:user:{user_id}"
    with _redis_client.pipeline() as pipeline:
        pipeline.set(record_key, json.dumps(record, ensure_ascii=False, separators=(",", ":")))
        pipeline.sadd("images:all", image_id)
        pipeline.sadd(user_index_key, image_id)
        pipeline.execute()
    return image_id

# 将图片bytes转换成base_uri
def process_image(image_data : bytes) -> str:
    # 将字节码转成 可操作的图像字节码对象
    img = Image.open(BytesIO(image_data))

    # 获取图片 MIME 类型，确保发送给多模态模型的是合法 data URI。
    image_mime = Image.MIME.get(img.format, "image/jpeg")

    # 获得base64字节码
    image_base64 = base64.b64encode(image_data).decode("utf-8")

    # 拼接uri
    data_uri = f"data:{image_mime};base64,{image_base64}"
    return data_uri

# 调用大模型生成图片文字描述信息
def describe_image(image_data : bytes) -> str:
    try:
        # 1. 先把图片转化成base64格式的字节码
        data_uri = process_image(image_data)
        # 2. 构建LangChain的请求（这里借助的是大模型的多模态能力，所以不使用模板提示词构建，模板提示词适用于大模型聊天能力）
        human_content = [
            {"image": data_uri},
            {
                "text": (
                    "用一句话简要地概括这张图片的内容，"
                    "并给出3到5个关键词（使用逗号分隔开），不要过多地解释"
                )
            },
        ]
        # 3. 构建访问大模型的实例
        vl_llm = ChatTongyi(
            model_name="qwen-vl-max",
            temperature=0.0,
            dashscope_api_key=API_KEY
        )
        # 4. 把访问大模型得到的结果进行处理返回
        resp = vl_llm.invoke([HumanMessage(content=human_content)])
        if isinstance(resp.content, str):
            return resp.content.strip()
        if isinstance(resp.content, list):
            return "".join(
                item.get("text", "")
                for item in resp.content
                if isinstance(item, dict)
            ).strip()
        return str(resp.content).strip()
    except Exception:
        app.logger.exception("生成图片文字描述信息失败")
        return ""




# 对图片的描述信息做最终的判定
def validate_image(image_desc : str) -> bool:
    try:
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    "你是一个图片审核助手，当前的业务只允许两种图片："
                    "1) 衣服/服装/穿搭相关;"
                    "2) 人物人像(人脸照、半身照、全身照).\n"
                    "需要你来判断当前图片的内容是否是以上两类图片，如果是输出是，如果否输出否。"
                    "请严格只输出是或者否，不要别的内容"
                ),
                (
                    "human", f"图片文字描述: {image_desc}"
                )
            ]
        )

        llm = ChatTongyi(
            model_name="qwen-plus",
            temperature=0.7,
            dashscope_api_key=API_KEY
        )

        resp = llm.invoke(prompt.format_messages())
        text = resp.content
        return text.startswith("是")
    except Exception:
        app.logger.exception("图片内容审核判定失败")
        return False



# 定义审核图片的接口路由
@app.route("/api/validate-image", methods = ["POST"])
def validate_image_api():
    try:
        # 从请求中获取可操作的file对象
        file = request.files["file"]

        # 从文件中读出字节码
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc)     # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        return jsonify({"code":200, "allow": allow}), 200
    except Exception:
        app.logger.exception("执行图片审核接口失败")
        return jsonify({"code":500, "allow": False}), 500


@app.route("/api/upload-image", methods=["POST"])
def upload_image_for_search_api():
    """接收 OSS 图片地址，生成描述和 CLIP 向量后保存到 Redis。"""
    try:
        request_data = request.get_json(silent=True) or {}
        oss_url = request_data.get("ossUrl")
        user_id = request_data.get("userId")

        if not isinstance(oss_url, str) or not oss_url.strip():
            return jsonify({"success": False, "error": "ossUrl不能为空"}), 400
        if user_id is None or str(user_id).strip() == "":
            return jsonify({"success": False, "error": "userId不能为空"}), 400

        # Java 上传成功后传入的是 OSS 地址，Python 服务负责下载图片内容。
        image_data = download_image_from_oss(oss_url.strip())

        # 使用 qwen-vl-max 生成可用于文搜图的文字描述。
        description = describe_image(image_data)
        if not description:
            raise RuntimeError("生成图片描述失败")

        # 使用本地 CLIP 模型生成可用于图搜图的 512 维向量。
        embedding = generate_image_embedding(image_data)

        # 描述、向量、用户 ID 和 OSS 地址统一保存为一条 JSON 记录。
        image_id = save_image_search_record(
            user_id=user_id,
            oss_url=oss_url.strip(),
            description=description,
            embedding=embedding,
        )

        return jsonify(
            {
                "description": description,
                "embeddingDim": len(embedding),
                "imageId": image_id,
                "success": True,
            }
        ), 200
    except requests.HTTPError:
        app.logger.exception("下载 OSS 图片失败")
        return jsonify({"success": False, "error": "无法下载 OSS 图片，请检查图片地址和访问权限"}), 502
    except redis.RedisError:
        app.logger.exception("保存图片搜索记录到 Redis 失败")
        return jsonify({"success": False, "error": "图片搜索记录保存失败"}), 503
    except Exception as e:
        app.logger.exception("处理图片搜索数据失败")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/search-image", methods=["POST"])
def search_image_api():
    """根据文本或查询图片，在当前用户的 Redis 图片缓存中检索相似图片。"""
    try:
        # userId、query 和 file 都从 multipart/form-data 中读取。
        user_id_value = (request.form.get("userId") or "").strip()
        if not user_id_value:
            return jsonify({"code": 400, "message": "userId不能为空", "data": []}), 400
        try:
            user_id = int(user_id_value)
        except ValueError:
            return jsonify({"code": 400, "message": "userId必须是整数", "data": []}), 400
        if user_id <= 0 or user_id > 9223372036854775807:
            return jsonify({"code": 400, "message": "userId超出有效范围", "data": []}), 400

        uploaded_file = request.files.get("file")
        query = (request.form.get("query") or "").strip()
        has_file = uploaded_file is not None
        has_query = bool(query)

        # 每次请求只能选择一种检索方式：文本查询或图片查询。
        if has_file == has_query:
            return jsonify({"code": 400, "message": "file和query必须且只能提供一个", "data": []}), 400

        # 在查询缓存前先校验图片输入，避免空索引掩盖无效的查询文件。
        query_image_data = None
        if has_file:
            if uploaded_file.filename == "":
                return jsonify({"code": 400, "message": "查询图片不能为空", "data": []}), 400
            query_image_data = uploaded_file.stream.read(IMAGE_MAX_SIZE_BYTES + 1)
            if not query_image_data:
                return jsonify({"code": 400, "message": "查询图片不能为空", "data": []}), 400
            if len(query_image_data) > IMAGE_MAX_SIZE_BYTES:
                return jsonify({"code": 413, "message": "查询图片大小超过限制", "data": []}), 413
            try:
                with Image.open(BytesIO(query_image_data)) as query_image:
                    query_image.verify()
            except Exception:
                return jsonify({"code": 400, "message": "查询文件不是有效图片", "data": []}), 400

        # 只读取当前用户在旧版上传流程中建立的索引和图片缓存。
        records = get_user_image_records(user_id)
        if not records:
            return jsonify({"code": 200, "message": "查询成功", "data": []}), 200

        matches = []

        if has_query:
            # 文搜图直接比较查询文本与 Redis 描述，不再调用 CLIP 文本编码器。
            candidate_records = [
                record for record in records
                if isinstance(record.get("description"), str) and record["description"].strip()
            ]
            for record in candidate_records:
                similarity = text_similarity(query, record["description"])
                # 文本检索只返回相似度严格高于 0.5 的结果。
                if similarity > 0.5:
                    file_path = record.get("ossUrl") or record.get("filePath") or record.get("url")
                    if file_path:
                        matches.append({"filePath": file_path, "similarity": similarity})
        else:
            # 使用本地 CLIP 模型提取查询图片的 512 维向量。
            query_embedding = generate_image_embedding(query_image_data)
            for record in records:
                cached_embedding = parse_cached_embedding(record)
                similarity = cosine_similarity(query_embedding, cached_embedding)
                # 图像检索只返回相似度严格高于 0.7 的结果。
                if similarity is not None and similarity > 0.7:
                    file_path = record.get("ossUrl") or record.get("filePath") or record.get("url")
                    if file_path:
                        matches.append({"filePath": file_path, "similarity": similarity})

        # 两种检索模式都按相似度从高到低排序，响应中保留四位小数。
        matches.sort(key=lambda item: item["similarity"], reverse=True)
        result_data = [
            {"filePath": item["filePath"], "similarity": round(item["similarity"], 4)}
            for item in matches
        ]
        return jsonify({"code": 200, "message": "查询成功", "data": result_data}), 200

    except redis.RedisError:
        app.logger.exception("读取图片搜索 Redis 缓存失败")
        return jsonify({"code": 503, "message": "图片检索缓存暂时不可用", "data": []}), 503
    except Exception:
        app.logger.exception("图片检索失败")
        return jsonify({"code": 500, "message": "图片检索失败", "data": []}), 500



# 全局的agent智能体
deep_agent=None

# 配置大模型实例参数
llm=ChatTongyi(
    model_name="qwen-plus",
    temperature=0.1,
    dashscope_api_key=API_KEY
)


def extract_dashscope_image_url(response, operation: str) -> str:
    """校验 DashScope 响应并提取生成图片 URL，同时记录服务端错误元数据。"""
    if not isinstance(response, dict):
        app.logger.error(
            "DashScope image request returned unexpected response type: operation=%s type=%s",
            operation,
            type(response).__name__,
        )
        raise RuntimeError("DashScope 返回了无法识别的响应")

    status_code = response.get("status_code")
    error_code = response.get("code")
    error_message = response.get("message")
    dashscope_request_id = response.get("request_id")
    output = response.get("output")

    # 非 200 或没有 output 通常表示 DashScope 拒绝/未能处理请求；记录其错误码以便定位。
    if status_code != 200 or not isinstance(output, dict):
        app.logger.error(
            "DashScope image request failed: operation=%s status_code=%s code=%s "
            "message=%s dashscope_request_id=%s output_present=%s",
            operation,
            status_code,
            error_code,
            error_message,
            dashscope_request_id,
            output is not None,
        )
        raise RuntimeError(
            f"DashScope 图片接口失败（status_code={status_code}, code={error_code}）"
        )

    choices = output.get("choices")
    try:
        content = choices[0]["message"]["content"]
        image_url = next(
            item.get("image")
            for item in content
            if isinstance(item, dict) and item.get("image")
        )
    except (IndexError, KeyError, TypeError, StopIteration):
        app.logger.error(
            "DashScope image response has no image URL: operation=%s status_code=%s "
            "code=%s message=%s dashscope_request_id=%s output_keys=%s",
            operation,
            status_code,
            error_code,
            error_message,
            dashscope_request_id,
            list(output.keys()),
        )
        raise RuntimeError("DashScope 响应中没有生成图片地址")

    return image_url


# 创建工具
@tool
def edit_image_tool(image_path:str,instruction:str)->str:
    """编辑单张图片"
    输入本地图片地址路径与编辑指令  调用Dashscope的图片编辑模型生成新的URL
    返回JSON字符串：{"success":true,"url":"..."}或{"success":false,"error":"..."}
    """
    try:
        # 读取文件内容
        with open(image_path, "rb") as f:
            image_data = f.read()

        # 转换字节文件
        image_data_uri = process_image(image_data)

        # 提示词构建
        messages = [
            {
                "role": "user",
                "content": [
                    {
                        "image": image_data_uri
                    },
                    {
                        "text": instruction
                    }
                ]
            }
        ]
        # 构建请求大模型参数
        params = {
            "model": DASHSCOPE_IMAGE_MODEL,
            "messages": messages
        }

        # 调用Dashscope编辑模型
        response = MultiModalConversation.call(**params)
        # 校验服务端状态并获取生成图片地址；失败时会记录 DashScope 错误码和请求 ID。
        url = extract_dashscope_image_url(response, "edit")
        # 将python文件从dict转为字符串，ensure_ascii=False：允许非ASCII字符（如中文）正常显示
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        app.logger.exception("编辑图片工具调用模型失败")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


# 合并两张图片工具
@tool
def merge_image_tool(image_path1:str, image_path2:str, instruction:str)->str:
    """合并两张图片
    输入两张本地图片路径和合并指令，调用 DashScope 图片编辑模型生成新的 URL。
    返回 JSON 字符串：{"success":true,"url":"..."} 或
    {"success":false,"error":"..."}
    """
    try:
        # 读取两张图片
        with open(image_path1, "rb") as f:
            image_data1 = f.read()
        with open(image_path2, "rb") as f:
            image_data2 = f.read()

        # 转换为图片数据 URI
        image_data_uri1 = process_image(image_data1)
        image_data_uri2 = process_image(image_data2)

        # 构建多图合并请求
        messages = [
            {
                "role": "user",
                "content": [
                    {"image": image_data_uri1},
                    {"image": image_data_uri2},
                    {"text": instruction},
                ],
            }
        ]
        params = {
            "model": DASHSCOPE_IMAGE_MODEL,
            "messages": messages,
        }

        response = MultiModalConversation.call(**params)
        url = extract_dashscope_image_url(response, "merge")
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        app.logger.exception("合并图片工具调用模型失败")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


# 声明工具
skills_tools=[edit_image_tool, merge_image_tool]

# 创建agent
try:
    deep_agent = create_deep_agent(
        model=llm,
        tools=skills_tools,
        skills=["/skills/"],
        system_prompt=(
            "你是一个智能图片处理助手，你可以调用一个工具: \n"
            "- edit_image_tool：编辑单张图片；只有收到一个 image_path 时使用它\n"
            "- merge_image_tool：合并两张图片；收到 image_path1 和 image_path2 时必须使用它\n"
            "如果同时存在 image_path1 和 image_path2，禁止调用 edit_image_tool。\n"
            "最终的输出格式JSON：{\"success\":true,\"url\":\"字符串类型\"} 或者{\"success\":false,\"error\":\"字符串类型\"}"
        )
    )
    app.logger.info("图片处理 Agent 已启用")
except Exception as e:
    app.logger.exception("图片处理 Agent 初始化失败")
    deep_agent=None


# agent执行
def invoke_agent(param: str, request_id: str) -> dict:
    """调用图片处理 Agent，并将无法解析的响应作为真正异常交给接口层记录。"""
    if deep_agent is None:
        raise RuntimeError("图片处理 Agent 未成功初始化")

    app.logger.info("request_id=%s stage=agent_invoke_started", request_id)
    state = deep_agent.invoke(
        {
            "messages": [
                {
                    "role": "user",
                    "content": param,
                }
            ]
        }
    )

    messages = (state or {}).get("messages") or []
    if not messages:
        raise RuntimeError("Agent 未返回任何消息")

    content = messages[-1].content
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("Agent 返回内容为空或格式不正确")

    # 解析 Agent 输出的 JSON；解析错误会由接口层记录带行号的完整堆栈。
    result = json.loads(content)
    if not isinstance(result, dict):
        raise RuntimeError("Agent 返回的 JSON 顶层必须是对象")
    return result


class ImageRequestError(ValueError):
    """表示 multipart 请求缺少必填参数或图片文件无效。"""

# 定义编辑/合并图片的的接口路由
def skill_image(request_id: str) -> str:
    """保存上传图片到临时文件，调用 Agent 后无论成功失败都清理临时文件。"""
    temp_paths = []
    try:
        instruction = (request.form.get("instruction") or "").strip()
        if not instruction:
            raise ImageRequestError("instruction 不能为空")

        file1 = request.files.get("file1")
        file2 = request.files.get("file2")
        is_merge_request = file1 is not None or file2 is not None
        prompt_lines = [
            "你必须调用一个工具，并且只输出 JSON格式",
            f"instruction:{instruction}",
        ]

        if is_merge_request:
            if file1 is None or file2 is None:
                raise ImageRequestError("合并图片必须同时提供 file1 和 file2")
            image_data1 = file1.read()
            image_data2 = file2.read()
            if not image_data1 or not image_data2:
                raise ImageRequestError("合并图片文件不能为空")

            path1 = os.path.join(tempfile.gettempdir(), f"aiwear_file1_{uuid.uuid4().hex}.bin")
            path2 = os.path.join(tempfile.gettempdir(), f"aiwear_file2_{uuid.uuid4().hex}.bin")
            with open(path1, "wb") as image_file:
                image_file.write(image_data1)
            temp_paths.append(path1)
            with open(path2, "wb") as image_file:
                image_file.write(image_data2)
            temp_paths.append(path2)

            prompt_lines.insert(0, "你必须调用 merge_image_tool，并且只输出 JSON 格式")
            prompt_lines.extend([f"image_path1:{path1}", f"image_path2:{path2}"])
            mode = "merge"
            file_sizes = (len(image_data1), len(image_data2))
        else:
            image_file = request.files.get("file")
            if image_file is None:
                raise ImageRequestError("请提供 file，或同时提供 file1 和 file2")
            image_data = image_file.read()
            if not image_data:
                raise ImageRequestError("上传图片不能为空")

            image_path = os.path.join(tempfile.gettempdir(), f"aiwear_{uuid.uuid4().hex}.bin")
            with open(image_path, "wb") as temp_file:
                temp_file.write(image_data)
            temp_paths.append(image_path)

            prompt_lines.insert(0, "你必须调用 edit_image_tool，并且只输出 JSON 格式")
            prompt_lines.append(f"image_path:{image_path}")
            mode = "edit"
            file_sizes = (len(image_data),)

        # 只记录模式和字节数，不记录提示词、图片内容或原始文件名。
        app.logger.info(
            "request_id=%s stage=image_request_validated mode=%s file_sizes=%s",
            request_id,
            mode,
            file_sizes,
        )
        result = invoke_agent("\n".join(prompt_lines), request_id)

        if result.get("success") is not True:
            # 工具可能将模型异常编码为 JSON 返回；这里把错误原因写入服务日志。
            app.logger.error(
                "request_id=%s stage=agent_reported_failure detail=%s",
                request_id,
                result.get("error", "未提供错误原因"),
            )
            raise RuntimeError("图片处理 Agent 执行失败")

        image_url = result.get("url")
        if not isinstance(image_url, str) or not image_url.strip():
            raise RuntimeError("Agent 响应缺少有效的图片 url 字段")

        app.logger.info("request_id=%s stage=image_processing_completed mode=%s", request_id, mode)
        return image_url.strip()
    finally:
        for temp_path in temp_paths:
            try:
                os.remove(temp_path)
            except FileNotFoundError:
                pass
            except OSError:
                app.logger.exception(
                    "request_id=%s stage=temp_file_cleanup_failed path=%s",
                    request_id,
                    temp_path,
                )

@app.route("/api/skill/image", methods=["POST"])
@app.route("/api/skill-image", methods=["POST"])
def skill_image_api():
    request_id = uuid.uuid4().hex
    is_merge_request = "file1" in request.files or "file2" in request.files
    mode = "merge" if is_merge_request else "edit"
    app.logger.info("request_id=%s route=image_skill_started mode=%s", request_id, mode)
    try:
        out = skill_image(request_id)
        if is_merge_request:
            return jsonify({"url": out, "success": True}), 200
        return jsonify({"success": True, "out": out}), 200
    except ImageRequestError as e:
        app.logger.warning("request_id=%s route=image_skill_invalid_request detail=%s", request_id, e)
        return jsonify({"success": False, "error": str(e), "requestId": request_id}), 400
    except Exception:
        # 记录完整堆栈；响应只返回 requestId，便于在日志中定位而不泄露内部细节。
        app.logger.exception("request_id=%s route=image_skill_failed mode=%s", request_id, mode)
        return jsonify(
            {
                "success": False,
                "error": "图片处理失败，请查看 Python 服务日志",
                "requestId": request_id,
            }
        ), 500






# 服务启动函数
if __name__ == "__main__":
    app.logger.info("AI服务启动成功！")
    app.run(debug=True, host="0.0.0.0", port=5000)
