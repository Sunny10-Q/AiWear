package com.sunny.aiwear_b.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.MergeImageRequest;
import com.sunny.aiwear_b.dto.request.SearchImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.FileUploadResponse;
import com.sunny.aiwear_b.dto.response.MergeImageResponse;
import com.sunny.aiwear_b.dto.response.SearchImageResponse;
import com.sunny.aiwear_b.entity.FileRecord;
import com.sunny.aiwear_b.mapper.FileMapper;
import com.sunny.aiwear_b.service.FileService;
import com.sunny.aiwear_b.service.PythonImageService;
import com.sunny.aiwear_b.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 文件业务服务实现类。
 *
 * 处理图片校验、OSS 上传和 files 表落库。只有 OSS 上传和数据库写入
 * 都成功后，接口才会返回成功；如果数据库写入失败，会尝试删除已上传的 OSS 文件。
 */
@Service
@Slf4j
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 文件记录 Mapper。 */
    @Autowired
    private FileMapper fileMapper;

    /** 用于校验令牌并解析上传用户 ID。 */
    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 图片搜索索引服务。图片上传和数据库入库成功后，再调用 Python 生成描述和向量。
     */
    @Autowired
    private PythonImageService pythonImageService;

    /** OSS 服务地址，例如 oss-cn-beijing.aliyuncs.com。 */
    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    /** OSS AccessKey ID，从环境变量注入。 */
    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    /** OSS AccessKey Secret，从环境变量注入。 */
    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    /** OSS Bucket 名称。 */
    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    /** 单个文件允许上传的最大字节数。 */
    @Value("${aliyun.oss.max-size:52428800}")
    private long maxSize;

    /**
     * 校验图片、上传 OSS，并保存文件元数据。
     */
    @Override
    public FileUploadResponse uploadImage(MultipartFile file, String authorization) {
        // 先校验文件，避免无效文件占用 OSS 资源。
        validateFile(file);

        // 在 OSS 上传之前调用 Python 审核接口，只有审核通过才继续后续流程。
        if (!validateImage(file)) {
            throw new IllegalArgumentException("图片未通过审核，仅支持服装、穿搭或人物图片");
        }

        // 从登录令牌中获取当前上传用户，用于写入 files.user_id。
        Long userId = jwtUtil.getUserId(authorization);
        String originalFileName = resolveFileName(file.getOriginalFilename());
        String objectKey = buildObjectKey(originalFileName);
        OSS ossClient = null;
        boolean uploaded = false;

        try {
            validateOssConfiguration();
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            if (StringUtils.hasText(file.getContentType())) {
                metadata.setContentType(file.getContentType());
            }

            // 先上传 OSS，上传成功后再将访问地址写入数据库。
            ossClient.putObject(bucketName, objectKey, file.getInputStream(), metadata);
            uploaded = true;

            String ossUrl = buildObjectUrl(objectKey);

            FileRecord record = new FileRecord();
            record.setUserId(userId);
            record.setFileName(originalFileName);
            record.setFileSize(file.getSize());
            record.setOssUrl(ossUrl);
            // 数据库写入失败时抛出异常，由 catch 块清理 OSS 文件。
            if (fileMapper.insert(record) != 1) {
                throw new IllegalStateException("图片记录写入数据库失败");
            }

            // 搜索索引属于上传后的扩展处理。Python 服务失败时保留原有上传结果，避免影响主流程。
            try {
                pythonImageService.processImage(userId, ossUrl);
            } catch (Exception pythonException) {
                log.warn("图片已上传并入库，但调用 Python 图片搜索服务失败，ossUrl={}", ossUrl, pythonException);
            }

            return new FileUploadResponse(ossUrl, originalFileName, file.getSize());
        } catch (Exception e) {
            if (uploaded && ossClient != null) {
                try {
                    ossClient.deleteObject(bucketName, objectKey);
                } catch (Exception cleanupException) {
                    log.warn("数据库写入失败，OSS文件清理失败，objectKey={}", objectKey, cleanupException);
                }
            }
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalStateException("图片上传失败", e);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /**
     * 获取图片列表
     * @param authorization
     * @return
     */
    @Override
    public List<FileRecord> myImages(String authorization) {

        // 从登录令牌中获取当前上传用户，用于写入 files.user_id。
        Long userId = jwtUtil.getUserId(authorization);

        //通过userId拿到当前用户的图片
        LambdaQueryWrapper<FileRecord> queryWrapper = new LambdaQueryWrapper<>();
        //按照Id继续倒排（最新上传的在前方）
        queryWrapper.eq(FileRecord::getUserId, userId).orderByDesc(FileRecord::getId);

        return fileMapper.selectList(queryWrapper);
    }

    /**
     * 校验上传的文件
     * @param file
     * @return
     */
    @Override
    public boolean validateImage(MultipartFile file) {
        // 将 MultipartFile 交给 PythonImageService，
        // 由它负责组装 multipart 请求并解析 Flask 返回的 allow 字段。
        return pythonImageService.validateImage(file);
    }

    /**
     * 搜索用户上传的图片
     * @param authorization
     * @param searchImageRequest
     * @return
     */
    @Override
    public List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest) {

        //1.获取用户Id
        Long userId = jwtUtil.getUserId(authorization);

        //获取当前用户上传的所有图片
        List<FileRecord> imageFiles = myImages(authorization);
        Map<String,String> map=new HashMap<>();
        for(FileRecord imageFile:imageFiles){
            map.put(imageFile.getOssUrl(),imageFile.getFileName());
        }
        //2.调用python服务
        List<SearchImageResponse> searchImageResponseList =pythonImageService.search(userId,searchImageRequest);
        for(SearchImageResponse imageResponse:searchImageResponseList){
            imageResponse.setFileName(map.get(imageResponse.getFilePath()));
        }
        //3.封装返回数据

        return searchImageResponseList;
    }

    /**
     * 图片编辑
     * @param authorization
     * @param editImageRequest
     * @return
     */
    @Override
    public EditImageResponse edit(String authorization, EditImageRequest editImageRequest) {

        //获取用户Id
        Long userId = jwtUtil.getUserId(authorization);
        //获取当前用户上传的所有图片
        List<FileRecord> imageFiles = myImages(authorization);
        //判断当前图片地址是否包含在imageFiles的ossUrl字段集合中
        List<String> urls = imageFiles.stream().map(FileRecord::getOssUrl).toList();
        if(!urls.contains(editImageRequest.getImage())){
            throw new RuntimeException("只能编辑自己上传的图片");
        }

        //调用python服务进行图片编辑
        EditImageResponse editImageResponse = pythonImageService.edit(userId,editImageRequest);
        return editImageResponse;
    }

    /**
     * 图片合并
     * @param authorization
     * @param mergeImageRequest
     * @return
     */
    @Override
    public MergeImageResponse merge(String authorization, MergeImageRequest mergeImageRequest) {
        //鉴权：用户只能编辑自己上传的图片
        List<FileRecord> imageFiles = myImages(authorization);
        //判断当前图片地址是否包含在imageFiles的ossURL字段集合中
        List<String> list = imageFiles.stream().map(FileRecord::getOssUrl).toList();
        if (!list.contains(mergeImageRequest.getImage1())||!list.contains(mergeImageRequest.getImage2())) {
            throw new RuntimeException("只能合并自己上传的图片");
        }
        //调用python服务
        return pythonImageService.merge(mergeImageRequest);

    }


    /**
     * 校验文件非空、大小和 MIME 类型。
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("图片大小不能超过" + maxSize + "字节");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("只支持上传图片文件");
        }
    }

    /**
     * 确认 OSS 必要配置已经通过环境变量注入。
     */
    private void validateOssConfiguration() {
        if (!StringUtils.hasText(endpoint)
                || !StringUtils.hasText(accessKeyId)
                || !StringUtils.hasText(accessKeySecret)
                || !StringUtils.hasText(bucketName)) {
            throw new IllegalStateException("OSS配置不完整，请检查环境变量");
        }
    }

    /**
     * 去除客户端可能传入的路径，只保留原始文件名。
     */
    private String resolveFileName(String originalFileName) {
        if (!StringUtils.hasText(originalFileName)) {
            return "image";
        }
        String fileName = originalFileName.replace('\\', '/');
        int slashIndex = fileName.lastIndexOf('/');
        return slashIndex >= 0 ? fileName.substring(slashIndex + 1) : fileName;
    }

    /**
     * 生成按日期归档、带随机值的 OSS 对象名，避免同名覆盖。
     */
    private String buildObjectKey(String fileName) {
        String extension = StringUtils.getFilenameExtension(fileName);
        String suffix = !StringUtils.hasText(extension)
                ? ""
                : "." + extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "images/" + LocalDate.now().format(DATE_FORMATTER) + "/"
                + UUID.randomUUID().toString().replace("-", "") + suffix;
    }

    /**
     * 根据 Bucket、Endpoint 和对象名拼接访问地址。
     */
    private String buildObjectUrl(String objectKey) {
        String normalizedEndpoint = endpoint.trim();
        if (normalizedEndpoint.startsWith("https://")) {
            normalizedEndpoint = normalizedEndpoint.substring("https://".length());
        } else if (normalizedEndpoint.startsWith("http://")) {
            normalizedEndpoint = normalizedEndpoint.substring("http://".length());
        }
        while (normalizedEndpoint.endsWith("/")) {
            normalizedEndpoint = normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1);
        }
        return "https://" + bucketName + "." + normalizedEndpoint + "/" + objectKey;
    }
}
