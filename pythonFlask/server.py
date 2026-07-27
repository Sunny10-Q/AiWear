import base64
import os
from PIL import Image

from flask import Flask, request, jsonify
from io import BytesIO

from flask.cli import load_dotenv
from langchain_community.chat_models.tongyi import ChatTongyi
from langchain_core.messages.human import HumanMessage
from langchain_core.prompts.chat import ChatPromptTemplate


# 创建Flask应用实例
app = Flask(__name__)

# 获取配置信息
load_dotenv()

API_KEY = os.getenv("DASHSCOPE_API_KEY")

# 将图片bytes转换成base_uri
def process_image(image_data : bytes) -> str:
    img = Image.open(BytesIO(image_data))
    image_format = (img.format).lower()
    image_base64 = base64.b64encode(image_data).decode("utf-8")
    data_uri = f"data:{image_format};base64,{image_base64}"
    return data_uri

# 调用大模型生成图片文字描述信息
def describe_image(image_data : bytes) -> str:
    try:
        # 1. 先把图片转化成base64
        data_uri = process_image(image_data)
        # 2. 构建LangChain的请求
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
        return resp.content[0]['text']
    except Exception as e :
        print(f"生成图片的文字描述信息出现异常:{e}")
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
    except Exception as e:
        print(f"做图片内容判定的时候出现异常:{e}")
        return False



# 定义审核图片的接口路由
@app.route("/api/validate-image", methods = ["POST"])
def validate_image_api():
    try:
        file = request.files["file"]
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc)     # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        return jsonify({"code":200, "allow": allow}), 200
    except Exception as e:
        print(f"执行审核图片操作捕获异常:{e}")
        return jsonify({"code":500, "allow": False}), 500



# 服务启动函数
if __name__ == "__main__":
    print("AI服务启动成功！")
    app.run(debug=True, host="0.0.0.0", port=5000)