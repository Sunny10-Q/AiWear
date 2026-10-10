// 当前类所在的 Java 包，impl 表示业务接口的具体实现类。
package com.sunny.aiwear_b.service.impl;

// Python 接口的请求参数对象，负责封装 userId 和 ossUrl。
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.PythonImageProcessRequest;
// Python 接口的响应对象，负责接收描述、向量维度和处理状态。
import com.sunny.aiwear_b.dto.request.SearchImageRequest;
import com.sunny.aiwear_b.dto.response.*;
// Python 图片审核接口的响应对象。
// 当前实现类需要实现的业务接口。
import com.sunny.aiwear_b.service.PythonImageService;
// Lombok 注解，用于自动生成日志对象 log。
import lombok.extern.slf4j.Slf4j;
// @Value 用于从 application.yml 读取配置。
import org.springframework.beans.factory.annotation.Value;
// Spring Boot 提供的 RestTemplate 构建器，用于配置并创建 RestTemplate。
import org.springframework.boot.web.client.RestTemplateBuilder;
// HTTP 请求实体和请求头类型。
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
// HTTP 响应包装对象，里面包含状态码和响应体。
import org.springframework.http.ResponseEntity;
// 用于构造 multipart/form-data 请求体。
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
// @Service 表示当前类交给 Spring 容器管理。
import org.springframework.stereotype.Service;
// Spring 提供的同步 HTTP 客户端，用来调用 Python Flask 接口。
import org.springframework.web.client.RestTemplate;
// 接收上传文件的 Spring 类型。
import org.springframework.web.multipart.MultipartFile;

// Java 的时间间隔类型，用于设置连接和读取超时时间。
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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

    // Python 审核接口的完整地址，例如 http://127.0.0.1:5000/api/validate-image。
    private final String validationEndpoint;

    // Python 图片搜索接口的完整地址，例如 http://127.0.0.1:5000/api/search-image。
    private final String searchEndpoint;

    // Python 图片编辑接口的完整地址。
    private final String editImageEndpoint;

    // 是否启用 Python 图片搜索服务，可通过配置文件关闭。
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    // 阿里云 OSS 上传配置，复用 application.yml 中已有的 aliyun.oss 配置。
    private final String ossEndpoint;
    private final String ossAccessKeyId;
    private final String ossAccessKeySecret;
    private final String ossBucketName;

    /**
     * 构造方法由 Spring 自动调用，并把配置和 RestTemplateBuilder 注入进来。
     *
     * @param restTemplateBuilder Spring 提供的 HTTP 客户端构建器
     * @param baseUrl Python Flask 服务的基础地址
     * @param uploadImagePath Python 图片处理接口路径
     * @param validateImagePath Python 图片审核接口路径
     * @param searchImagePath Python 图片搜索接口路径
     * @param editImagePath Python 图片编辑接口路径
     * @param connectTimeoutMs 建立 HTTP 连接的最大等待时间，单位毫秒
     * @param readTimeoutMs 等待 Python 返回结果的最大时间，单位毫秒
     * @param enabled 是否启用 Python 图片搜索服务
     * @param ossEndpoint 阿里云 OSS 服务地址
     * @param ossAccessKeyId 阿里云 OSS AccessKey ID
     * @param ossAccessKeySecret 阿里云 OSS AccessKey Secret
     * @param ossBucketName 阿里云 OSS Bucket 名称
     */
    public PythonImageServiceImpl(
            // Spring Boot 自动注入 RestTemplateBuilder。
            RestTemplateBuilder restTemplateBuilder,
            // 读取 Python 服务基础地址；冒号后面是配置不存在时使用的默认值。
            @Value("${python.image-service.base-url:http://127.0.0.1:5000}") String baseUrl,
            // 读取 Python 上传图片接口路径。
            @Value("${python.image-service.upload-image-path:/api/upload-image}") String uploadImagePath,
            // 读取 Python 图片审核接口路径。
            @Value("${python.image-service.validate-image-path:/api/validate-image}") String validateImagePath,
            // 读取 Python 图片搜索接口路径。
            @Value("${python.image-service.search-image-path:/api/search-image}") String searchImagePath,
            // 读取 Python 图片编辑接口路径。
            @Value("${python.image-service.edit-image-path:/api/skill/image}") String editImagePath,
            // 读取连接超时配置，默认 5000 毫秒。
            @Value("${python.image-service.connect-timeout-ms:5000}") int connectTimeoutMs,
            // 读取读取响应超时配置，默认 180000 毫秒，即 180 秒。
            @Value("${python.image-service.read-timeout-ms:180000}") int readTimeoutMs,
            // 读取服务开关，默认开启。
            @Value("${python.image-service.enabled:true}") boolean enabled,
            ObjectMapper objectMapper,
            // 读取 OSS 地址及凭据；敏感凭据不写入日志。
            @Value("${aliyun.oss.endpoint}") String ossEndpoint,
            @Value("${aliyun.oss.access-key-id}") String ossAccessKeyId,
            @Value("${aliyun.oss.access-key-secret}") String ossAccessKeySecret,
            @Value("${aliyun.oss.bucket-name}") String ossBucketName) {
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

        // 把基础地址和审核路径拼成完整审核接口地址。
        this.validationEndpoint = joinUrl(baseUrl, validateImagePath);

        // 把基础地址和搜索路径拼成完整搜索接口地址。
        this.searchEndpoint = joinUrl(baseUrl, searchImagePath);

        // 把基础地址和编辑路径拼成完整编辑接口地址。
        this.editImageEndpoint = joinUrl(baseUrl, editImagePath);

        // 保存服务开关，后续 processImage 方法会根据它决定是否发请求。
        this.enabled = enabled;
        this.objectMapper = objectMapper;
        this.ossEndpoint = ossEndpoint;
        this.ossAccessKeyId = ossAccessKeyId;
        this.ossAccessKeySecret = ossAccessKeySecret;
        this.ossBucketName = ossBucketName;
    }

    /**
     * 调用 Flask 的图片审核接口。
     *
     * <p>审核服务不可用时直接抛出异常，调用方必须阻止后续 OSS 上传，
     * 避免未经审核的图片进入系统。</p>
     */
    @Override
    public boolean validateImage(MultipartFile file) {
        // 审核是上传前置条件，服务关闭时不能默认放行。
        if (!enabled) {
            throw new IllegalStateException("Python 图片审核服务未启用，无法上传图片");
        }
        // 防止向 Python 服务发送空文件。
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件不能为空");
        }

        // 创建 multipart/form-data 请求体。
        LinkedMultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        // 字段名必须是 file，对应 Flask 中的 request.files["file"]。
        multipartBody.add("file", file.getResource());

        // 创建 HTTP 请求头，并声明请求内容类型为 multipart/form-data。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 把请求头和 multipart 请求体组合成完整的 HTTP 请求。
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(multipartBody, headers);

        // 向 Flask 审核接口发送 POST 请求，并把 JSON 响应转换成 Java 对象。
        ResponseEntity<PythonImageValidationResponse> response = restTemplate.postForEntity(
                validationEndpoint,
                requestEntity,
                PythonImageValidationResponse.class);

        // 从 HTTP 响应中读取 Python 返回的审核结果。
        PythonImageValidationResponse validationResponse = response.getBody();
        // HTTP 请求失败或响应为空时，抛出异常并阻止上传。
        if (!response.getStatusCode().is2xxSuccessful() || validationResponse == null) {
            throw new IllegalStateException("Python 图片审核服务返回了无效响应，HTTP状态码="
                    + response.getStatusCode().value());
        }

        // Flask 返回 allow=true 才算审核通过，其他情况全部拒绝上传。
        return Boolean.TRUE.equals(validationResponse.getAllow());
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
     * 调用 Python 图片搜索接口，支持文本搜图和图片搜图。
     *
     * @param userId 当前登录用户 ID
     * @param searchImageRequest 包含 query 或 file 的搜索请求
     * @return Python 返回的相似图片列表
     */
    @Override
    public List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest) {
        if (!enabled) {
            throw new IllegalStateException("Python 图片编辑服务未启用");
        }

        // 校验用户 ID，Python 接口会按 userId 限定 Redis 图片索引范围。
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 无效");
        }

        // 校验搜索请求对象，避免访问空对象的字段。
        if (searchImageRequest == null) {
            throw new IllegalArgumentException("搜索参数不能为空");
        }

        // 读取文本条件，并去除用户输入两端的空白字符。
        String query = searchImageRequest.getQuery();
        query = query == null ? null : query.trim();

        // 读取上传的查询图片；空 MultipartFile 不视为有效图片查询。
        MultipartFile file = searchImageRequest.getFile();
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasQuery = query != null && !query.isBlank();

        // 文搜图和图搜图必须二选一，和 Python 接口的参数约定保持一致。
        if (hasFile == hasQuery) {
            throw new IllegalArgumentException("文本 query 和查询图片 file 必须且只能提供一个");
        }

        // 创建 multipart/form-data 请求体，Python 从 request.form 读取 userId/query。
        LinkedMultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        // 将当前登录用户 ID 作为普通表单字段传给 Python。
        multipartBody.add("userId", userId.toString());

        if (hasFile) {
            // 以 file 字段名传输图片，对应 Flask 的 request.files.get("file")。
            multipartBody.add("file", file.getResource());
        } else {
            // 文本查询以 query 字段传输，对应 Flask 的 request.form.get("query")。
            multipartBody.add("query", query);
        }

        // 设置 multipart 请求头；RestTemplate 会自动生成 multipart boundary。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 将请求头和表单内容封装为 HTTP 请求实体。
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(multipartBody, headers);

        // 调用 Flask /api/search-image，并把 JSON 响应映射到 Python 搜索响应 DTO。
        ResponseEntity<PythonImageSearchResponse> response = restTemplate.postForEntity(
                searchEndpoint,
                requestEntity,
                PythonImageSearchResponse.class);

        // 获取 Python 返回的响应对象。
        PythonImageSearchResponse searchResponse = response.getBody();

        // 检查 HTTP 状态和响应体，防止把空响应当成搜索成功。
        if (!response.getStatusCode().is2xxSuccessful() || searchResponse == null) {
            throw new IllegalStateException("Python 图片搜索服务返回了无效响应，HTTP状态码="
                    + response.getStatusCode().value());
        }

        // Python 业务状态码不是 200 时，把 Python 返回的消息传递给上层。
        if (searchResponse.getCode() == null || searchResponse.getCode() != 200) {
            String message = searchResponse.getMessage() == null
                    ? "未知错误"
                    : searchResponse.getMessage();
            throw new IllegalStateException("Python 图片搜索失败：" + message);
        }

        // Python 没有匹配结果时 data 可能为空；统一返回不可变空列表。
        return searchResponse.getData() == null ? List.of() : searchResponse.getData();
    }

    /**
     * 调用python服务中的http://127.0.0.1:5000/api/skill/image 接口
     * @param userId
     * @param editImageRequest
     * @return
     */
    @Override
    public EditImageResponse edit(Long userId, EditImageRequest editImageRequest) {

        // Python 搜索服务关闭时不能返回伪造的空搜索结果。
        if (!enabled) {
            throw new IllegalStateException("Python 图片搜索服务未启用");
        }

        // 校验用户 ID，Python 接口会按 userId 限定 Redis 图片索引范围。
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 无效");
        }

        if (editImageRequest == null) {
            throw new IllegalArgumentException("图片编辑参数不能为空");
        }

        String sourceImageUrl = editImageRequest.getImage();
        String instruction = editImageRequest.getInstruction();
        if (sourceImageUrl == null || sourceImageUrl.isBlank()) {
            throw new IllegalArgumentException("原图片地址不能为空");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("图片编辑指令不能为空");
        }

        Path sourceTemp = null;
        Path editedTemp = null;
        try {
            // 下载 OSS 原图到临时文件，再以 multipart/form-data 交给 Python 编辑。
            sourceTemp = downloadToTempFile(sourceImageUrl);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(sourceTemp));
            body.add("instruction", instruction.trim());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    editImageEndpoint, requestEntity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Python 图片编辑服务返回了无效响应，HTTP状态码="
                        + response.getStatusCode().value());
            }

            JsonNode result = objectMapper.readTree(response.getBody());
            if (!result.path("success").asBoolean(false)) {
                String error = result.path("error").asText("未知错误");
                throw new IllegalStateException("Python 图片编辑失败：" + error);
            }

            // Python 单图编辑接口使用 out；同时兼容返回 url 字段的版本。
            String pythonUrl = result.path("url").asText("").trim();
            if (pythonUrl.isBlank()) {
                pythonUrl = result.path("out").asText("").trim();
            }
            if (pythonUrl.isBlank()) {
                throw new IllegalStateException("Python 图片编辑服务没有返回结果图片地址");
            }

            // 下载 Python 生成图，再将图片持久化到业务 OSS。
            editedTemp = downloadToTempFile(pythonUrl);
            String extension = getImageExtension(editedTemp);
            String contentType = getImageContentType(editedTemp, extension);
            String savedOssUrl = uploadEditedImageToOss(editedTemp, extension, contentType);

            // 返回 Python 生成地址和业务 OSS 持久化地址。
            EditImageResponse editImageResponse = new EditImageResponse();
            editImageResponse.setUrl(pythonUrl);
            editImageResponse.setSaveUrl(savedOssUrl);
            return editImageResponse;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Python 图片编辑服务返回了无效 JSON", e);
        } catch (IOException e) {
            throw new IllegalStateException("读取或上传编辑后的图片失败", e);
        } finally {
            // 无论 Python 调用、下载或 OSS 上传成功与否，都清理临时图片文件。
            deleteTempFile(sourceTemp);
            deleteTempFile(editedTemp);
        }
    }

    /**
     * 将编辑后的图片上传到 OSS，并返回可访问地址。
     */
    private String uploadEditedImageToOss(Path imagePath, String extension, String contentType) throws IOException {
        if (ossEndpoint == null || ossEndpoint.isBlank()
                || ossAccessKeyId == null || ossAccessKeyId.isBlank()
                || ossAccessKeySecret == null || ossAccessKeySecret.isBlank()
                || ossBucketName == null || ossBucketName.isBlank()) {
            throw new IllegalStateException("阿里云 OSS 配置不完整");
        }

        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectKey = "images/edited/" + datePath + "/"
                + UUID.randomUUID().toString().replace("-", "") + extension;
        OSS ossClient = null;
        try {
            ossClient = new OSSClientBuilder().build(ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(imagePath));
            metadata.setContentType(contentType);
            try (InputStream inputStream = Files.newInputStream(imagePath)) {
                ossClient.putObject(ossBucketName, objectKey, inputStream, metadata);
            }
            return buildOssObjectUrl(objectKey);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /**
     * 根据 OSS 配置拼接对象的 HTTPS 访问地址。
     */
    private String buildOssObjectUrl(String objectKey) {
        String normalizedEndpoint = ossEndpoint.trim();
        if (normalizedEndpoint.startsWith("https://")) {
            normalizedEndpoint = normalizedEndpoint.substring("https://".length());
        } else if (normalizedEndpoint.startsWith("http://")) {
            normalizedEndpoint = normalizedEndpoint.substring("http://".length());
        }
        while (normalizedEndpoint.endsWith("/")) {
            normalizedEndpoint = normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1);
        }
        return "https://" + ossBucketName + "." + normalizedEndpoint + "/" + objectKey;
    }

    /**
     * 下载 HTTP/HTTPS 图片到临时文件；下载失败时会删除未完成的临时文件。
     */
    private Path downloadToTempFile(String fileUrl) {
        Path tempFile = null;
        try {
            if (fileUrl == null || fileUrl.isBlank()) {
                throw new IllegalArgumentException("图片地址不能为空");
            }
            URI uri = URI.create(fileUrl.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("图片地址只支持 HTTP 或 HTTPS");
            }

            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(180_000);
            tempFile = Files.createTempFile("image-edit-", getUrlImageSuffix(uri));
            try (InputStream inputStream = connection.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            deleteTempFile(tempFile);
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalStateException("下载图片失败", e);
        }
    }

    /**
     * 从 URL 路径读取常见图片扩展名，临时文件保留扩展名以便识别 MIME 类型。
     */
    private String getUrlImageSuffix(URI uri) {
        String path = uri.getPath();
        if (path != null) {
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex >= 0) {
                String suffix = path.substring(dotIndex).toLowerCase(Locale.ROOT);
                if (List.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").contains(suffix)) {
                    return suffix;
                }
            }
        }
        return ".png";
    }

    /**
     * 读取临时图片扩展名，统一转成小写并限制为常见图片格式。
     */
    private String getImageExtension(Path imagePath) {
        String fileName = imagePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0) {
            String extension = fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
            if (List.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").contains(extension)) {
                return extension;
            }
        }
        return ".png";
    }

    /**
     * 根据扩展名生成上传时使用的 MIME 类型。
     */
    private String getImageContentType(Path imagePath, String extension) throws IOException {
        String contentType = Files.probeContentType(imagePath);
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        return switch (extension) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    /**
     * 删除临时文件；清理失败仅记录日志，不覆盖原始业务异常。
     */
    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("清理图片编辑临时文件失败：{}", tempFile, e);
        }
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
