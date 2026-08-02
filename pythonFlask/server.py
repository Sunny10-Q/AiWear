import base64
import json
import os
import tempfile
import uuid

from PIL import Image
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

# 创建Flask应用实例
app = Flask(__name__)

# 获取配置信息（从项目根目录下的 .env 文件中读取键值对，并将其加载到当前进程的环境变量（os.environ）中）
load_dotenv()
API_KEY = os.getenv("DASHSCOPE_API_KEY")

# 将图片bytes转换成base_uri
def process_image(image_data : bytes) -> str:
    # 将字节码转成 可操作的图像字节码对象
    img = Image.open(BytesIO(image_data))

    # 获取文件后缀
    image_format = (img.format).lower()

    # 获得base64字节码
    image_base64 = base64.b64encode(image_data).decode("utf-8")

    # 拼接uri
    data_uri = f"data:{image_format};base64,{image_base64}"
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
        # 从请求中获取可操作的file对象
        file = request.files["file"]

        # 从文件中读出字节码
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc)     # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        return jsonify({"code":200, "allow": allow}), 200
    except Exception as e:
        print(f"执行审核图片操作捕获异常:{e}")
        return jsonify({"code":500, "allow": False}), 500



# 全局的agent智能体
deep_agent=None

# 配置大模型实例参数
llm=ChatTongyi(
    model_name="qwen-plus",
    temperature=0.1,
    dashscope_api_key=API_KEY
)


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
            "model": "qwen-image-edit-plus",
            "messages": messages
        }

        # 调用Dashscope编辑模型
        response = MultiModalConversation.call(**params)
        # 获取url
        url = response['output']['choices'][0]['message']['content'][0]["image"]
        # 将python文件从dict转为字符串，ensure_ascii=False：允许非ASCII字符（如中文）正常显示
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用编辑模型报错：{e}")
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
            "model": "qwen-image-edit-plus",
            "messages": messages,
        }

        response = MultiModalConversation.call(**params)
        url = response["output"]["choices"][0]["message"]["content"][0]["image"]
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用合并模型报错：{e}")
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
    print("Agent 已启用")
except Exception as e:
    print("Agent未启用")
    deep_agent=None


# agent执行
def invoke_agent(param:str)->dict:
   try:
       # 执行
       state = deep_agent.invoke(
           {
               "messages": [
                   {
                       "role": "user",
                       "content": param
                   }
               ]
           }
       )

       msg = (state or {}).get("messages") or []

       last = msg[-1] if msg else None
       content = last.content
       # 解析json，将JSON字符串解析为Python字典
       obj = json.loads(content)
       return obj
   except Exception as e:
       print(f"agent函数执行异常:{e}")
       return {"success":False,"error":f"执行agent函数失败{e}"}

# 定义编辑/合并图片的的接口路由
def skill_image()->str:
   try:
       # 校验大模型实例是否为空
       if deep_agent is None:
           return ""
       # 获取参数（编辑指令）
       instruction = request.form.get("instruction")
       # 判断编辑指令是否为空
       if not instruction:
           return ""
       # 临时保存地址
       tmp_dir = tempfile.gettempdir()
       # 临时路径
       tem_paths = []
       # 提示词
       prompt_lines = [
           "你必须调用一个工具，并且只输出 JSON格式",
           f"instruction:{instruction}"
       ]
       # 新功能：file1 + file2 进入两图合并流程
       file1 = request.files.get("file1")
       file2 = request.files.get("file2")
       if file1 is not None or file2 is not None:
           if file1 is None or file2 is None:
               return ""

           p1 = os.path.join(tmp_dir, f"aiwear_file1_{uuid.uuid4().hex}.bin")
           p2 = os.path.join(tmp_dir, f"aiwear_file2_{uuid.uuid4().hex}.bin")
           with open(p1, "wb") as f:
               f.write(file1.read())
           with open(p2, "wb") as f:
               f.write(file2.read())

           tem_paths.extend([p1, p2])
           prompt_lines.insert(0, "你必须调用 merge_image_tool，并且只输出 JSON 格式")
           prompt_lines.append(f"image_path1:{p1}")
           prompt_lines.append(f"image_path2:{p2}")
       else:
           # 原有功能：file 进入单图编辑流程
           data = request.files["file"].read();
           p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
           with open(p,"wb") as f:
               f.write(data)

           tem_paths.append(p)
           prompt_lines.insert(0, "你必须调用 edit_image_tool，并且只输出 JSON 格式")
           prompt_lines.append(f"image_path:{p}")

       # 调用大模型进行处理
       out = invoke_agent("\n".join(prompt_lines))
       return out["url"]
   except Exception as e:
       print(f"执行skill异常{e}")
       return ""

@app.route("/api/skill/image", methods=["POST"])
@app.route("/api/skill-image", methods=["POST"])
def skill_image_api():
    try:
        out=skill_image()
        is_merge_request = "file1" in request.files or "file2" in request.files
        if is_merge_request:
            return jsonify({"url": out, "success": True}), 200
        return jsonify(
            {
                "success": True,
                "out":out,
            }
        ),200
    except Exception as e:
        print(f"调用skill处理失败{e}")
        return jsonify(
            {
                "success":False,
                "error":str(e)
            }
        ),500






# 服务启动函数
if __name__ == "__main__":
    print("AI服务启动成功！")
    app.run(debug=True, host="0.0.0.0", port=5000)
