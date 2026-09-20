// 当前类所在的 Java 包，impl 表示业务接口的具体实现类。
package com.sunny.aiwear_b.service.impl;

// Python 接口的请求参数对象，负责封装 userId 和 ossUrl。
import com.sunny.aiwear_b.dto.request.PythonImageProcessRequest;
// Python 接口的响应对象，负责接收描述、向量维度和处理状态。
import com.sunny.aiwear_b.dto.response.PythonImageProcessResponse;
// 当前实现类需要实现的业务接口。
import com.sunny.aiwear_b.service.PythonImageService;
// Lombok 注解，用于自动生成日志对象 log。
import lombok.extern.slf4j.Slf4j;
// @Value 用于从 application.yml 读取配置。
import org.springframework.beans.factory.annotation.Value;
// Spring Boot 提供的 RestTemplate 构建器，用于配置并创建 RestTemplate。
import org.springframework.boot.web.client.RestTemplateBuilder;
// HTTP 响应包装对象，里面包含状态码和响应体。
import org.springframework.http.ResponseEntity;
// @Service 表示当前类交给 Spring 容器管理。
import org.springframework.stereotype.Service;
// Spring 提供的同步 HTTP 客户端，用来调用 Python Flask 接口。
import org.springframework.web.client.RestTemplate;

// Java 的时间间隔类型，用于设置连接和读取超时时间。
import java.time.Duration;

/**
 * Python 图片搜索处理服务实现类。
 *
 * <p>Java 图片上传到 OSS 并写入数据库后，
 * 通过本类调用 Python 的 /api/upload-image 接口，
 * 让 Python 生成图片描述和 CLIP 向量。</p>
 */
// 告诉 Spring：把当前类注册为一个 Service Bean，可以自动注入到其他类中。
@Service
// 自动生成 private static final Logger log 日志对象。
@Slf4j
public class PythonImageServiceImpl implements PythonImageService {

    // HTTP 客户端对象，真正负责发送请求和接收响应。
    private final RestTemplate restTemplate;

    // Python 接口的完整地址，例如 http://127.0.0.1:5000/api/upload-image。
    private final String endpoint;

    // 是否启用 Python 图片搜索服务，可通过配置文件关闭。
    private final boolean enabled;

    /**
     * 构造方法由 Spring 自动调用，并把配置和 RestTemplateBuilder 注入进来。
     *
     * @param restTemplateBuilder Spring 提供的 HTTP 客户端构建器
     * @param baseUrl Python Flask 服务的基础地址
     * @param uploadImagePath Python 图片处理接口路径
     * @param connectTimeoutMs 建立 HTTP 连接的最大等待时间，单位毫秒
     * @param readTimeoutMs 等待 Python 返回结果的最大时间，单位毫秒
     * @param enabled 是否启用 Python 图片搜索服务
     */
    public PythonImageServiceImpl(
            // Spring Boot 自动注入 RestTemplateBuilder。
            RestTemplateBuilder restTemplateBuilder,
            // 读取 Python 服务基础地址；冒号后面是配置不存在时使用的默认值。
            @Value("${python.image-service.base-url:http://127.0.0.1:5000}") String baseUrl,
            // 读取 Python 上传图片接口路径。
            @Value("${python.image-service.upload-image-path:/api/upload-image}") String uploadImagePath,
            // 读取连接超时配置，默认 5000 毫秒。
            @Value("${python.image-service.connect-timeout-ms:5000}") int connectTimeoutMs,
            // 读取读取响应超时配置，默认 180000 毫秒，即 180 秒。
            @Value("${python.image-service.read-timeout-ms:180000}") int readTimeoutMs,
            // 读取服务开关，默认开启。
            @Value("${python.image-service.enabled:true}") boolean enabled) {
        // 创建 RestTemplate，并设置建立连接的最长等待时间。
        this.restTemplate = restTemplateBuilder
                // Duration.ofMillis 把整数毫秒转换为 Java 的时间间隔对象。
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // 设置等待 Python 处理结果的最长时间。
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                // build() 根据前面的配置创建真正可用的 RestTemplate。
                .build();

        // 把基础地址和接口路径拼成完整请求地址。
        this.endpoint = joinUrl(baseUrl, uploadImagePath);

        // 保存服务开关，后续 processImage 方法会根据它决定是否发请求。
        this.enabled = enabled;
    }

    /**
     * 调用 Flask 接口处理图片。
     *
     * <p>这里不吞掉异常，由上层决定是否影响主业务。当前文件上传流程会记录警告，
     * 但不会因为搜索索引服务暂时不可用而回滚已经完成的 OSS 上传和数据库入库。</p>
     */
    @Override
    public PythonImageProcessResponse processImage(Long userId, String ossUrl) {
        // 如果配置关闭了 Python 服务，则不发送 HTTP 请求。
        if (!enabled) {
            // debug 级别日志不会在普通生产日志中大量输出。
            log.debug("Python 图片搜索服务已禁用");
            // 返回 null 表示本次没有执行 Python 处理。
            return null;
        }

        // 调用接口前先校验必要参数，避免发送无效请求。
        if (userId == null || ossUrl == null || ossUrl.isBlank()) {
            // 参数错误属于调用方问题，因此抛出 IllegalArgumentException。
            throw new IllegalArgumentException("调用 Python 图片服务时 userId 和 ossUrl 不能为空");
        }

        // 创建请求体，字段名称会按照 Java 属性名序列化为 userId 和 ossUrl。
        PythonImageProcessRequest request = new PythonImageProcessRequest(ossUrl, userId);

        // 发送 POST 请求：
        // 1. endpoint 是 Python 接口地址；
        // 2. request 是要发送的 JSON 请求体；
        // 3. PythonImageProcessResponse.class 表示把返回 JSON 转成该 Java 对象。
        ResponseEntity<PythonImageProcessResponse> response = restTemplate.postForEntity(
                endpoint,
                request,
                PythonImageProcessResponse.class);

        // 从 HTTP 响应中取出 Python 返回的 JSON 数据。
        PythonImageProcessResponse body = response.getBody();

        // 检查 HTTP 状态码和响应体，避免后续对空对象进行操作。
        if (!response.getStatusCode().is2xxSuccessful() || body == null) {
            // value() 获取数字状态码，例如 200、400 或 500。
            throw new IllegalStateException("Python 图片服务返回了无效响应，HTTP状态码=" + response.getStatusCode().value());
        }

        // Python 接口可能 HTTP 返回 200，但业务字段 success 仍然为 false，因此还要检查业务状态。
        if (!Boolean.TRUE.equals(body.getSuccess())) {
            // Python 返回错误信息时使用它；没有错误信息时使用默认提示。
            String error = body.getError() == null ? "未知错误" : body.getError();
            // 把 Python 的业务失败转换成 Java 异常交给上层处理。
            throw new IllegalStateException("Python 图片服务处理失败：" + error);
        }

        // 返回处理结果，调用方可以读取 imageId、description 和 embeddingDim。
        return body;
    }

    /**
     * 拼接基础地址和接口路径，避免出现重复或缺失斜杠。
     *
     * @param baseUrl 服务基础地址，例如 http://127.0.0.1:5000/
     * @param path 接口路径，例如 /api/upload-image
     * @return 拼接后的完整 URL
     */
    private String joinUrl(String baseUrl, String path) {
        // baseUrl 为空时使用空字符串，否则去除首尾空白。
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        // path 为空时使用空字符串，否则去除首尾空白。
        String normalizedPath = path == null ? "" : path.trim();

        // 删除基础地址末尾多余的斜杠，避免出现 //api。
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }

        // 如果接口路径没有以斜杠开头，则自动补上一个斜杠。
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        // 返回最终请求地址。
        return normalizedBaseUrl + normalizedPath;
    }
}
