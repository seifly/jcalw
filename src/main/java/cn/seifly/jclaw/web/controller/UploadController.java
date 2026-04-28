package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传 API 控制器
 * 
 * 支持上传图片文件，存储到 workspace/uploads 目录下。
 * 
 * 请求格式（JSON）：
 * {
 *   "images": [
 *     {"data": "base64编码的图片数据", "name": "可选的文件名"}
 *   ]
 * }
 * 
 * 响应格式：
 * {
 *   "files": ["uploads/xxx.jpg", "uploads/yyy.png"]
 * }
 */
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class UploadController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    /** 上传文件存储子目录名 */
    private static final String UPLOADS_DIR = "uploads";
    
    /** 允许的图片 MIME 类型前缀 */
    private static final String IMAGE_MIME_PREFIX = "image/";
    
    /** 单个文件最大大小（10MB） */
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    
    @Autowired
    private Config config;
    
    /**
     * 上传图片文件
     * 
     * 请求体格式：{"images": [{"data": "data:image/jpeg;base64,...", "name": "photo.jpg"}]}
     * 响应格式：{"files": ["uploads/xxx.jpg"]}
     * 
     * @param request 包含 images 数组的请求体
     * @return 上传结果，包含保存后的文件路径列表
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> imagesList = (List<Map<String, Object>>) request.get("images");
            
            if (imagesList == null || imagesList.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No images provided");
                return ResponseEntity.status(400).body(error);
            }
            
            // 确保上传目录存在（使用 getWorkspacePath 展开 ~ 为用户主目录）
            String workspace = config.getWorkspacePath();
            Path uploadsPath = Paths.get(workspace, UPLOADS_DIR);
            Files.createDirectories(uploadsPath);
            
            List<String> filesArray = new ArrayList<>();
            
            for (Map<String, Object> imageNode : imagesList) {
                String data = (String) imageNode.getOrDefault("data", "");
                String name = (String) imageNode.getOrDefault("name", "");
                
                if (data.isEmpty()) {
                    continue;
                }
                
                try {
                    String savedPath = saveImage(uploadsPath, data, name);
                    filesArray.add(savedPath);
                } catch (Exception e) {
                    logger.warn("Failed to save image", Map.of("error", e.getMessage()));
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("files", filesArray);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Upload API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 保存 Base64 编码的图片到文件系统。
     * 
     * @param uploadsPath 上传目录路径
     * @param dataUri Base64 数据 URI（如 "data:image/jpeg;base64,..."）
     * @param originalName 原始文件名（可选）
     * @return 保存后的相对路径（如 "uploads/xxx.jpg"）
     */
    private String saveImage(Path uploadsPath, String dataUri, String originalName) throws Exception {
        // 解析 data URI
        if (!dataUri.startsWith("data:")) {
            throw new IllegalArgumentException("Invalid data URI format");
        }
        
        int commaIndex = dataUri.indexOf(',');
        if (commaIndex == -1) {
            throw new IllegalArgumentException("Invalid data URI format");
        }
        
        String header = dataUri.substring(5, commaIndex);  // "image/jpeg;base64"
        String base64Data = dataUri.substring(commaIndex + 1);
        
        // 解析 MIME 类型
        String mimeType = header.split(";")[0];
        if (!mimeType.startsWith(IMAGE_MIME_PREFIX)) {
            throw new IllegalArgumentException("Only image files are allowed");
        }
        
        // 解码 Base64
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        
        // 检查文件大小
        if (imageBytes.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large (max 10MB)");
        }
        
        // 生成文件名
        String extension = getExtensionFromMime(mimeType);
        String fileName = generateFileName(originalName, extension);
        
        // 保存文件
        Path filePath = uploadsPath.resolve(fileName);
        Files.write(filePath, imageBytes);
        
        // 验证文件是否实际存在
        if (Files.exists(filePath)) {
            logger.info("图片保存成功", Map.of(
                    "file_path", filePath.toString(),
                    "relative_path", UPLOADS_DIR + "/" + fileName,
                    "size", imageBytes.length));
        } else {
            logger.error("图片保存失败，文件不存在", Map.of("file_path", filePath.toString()));
        }
        
        // 返回相对路径（用于前端显示和存储）
        return UPLOADS_DIR + "/" + fileName;
    }
    
    /**
     * 根据 MIME 类型获取文件扩展名。
     */
    private String getExtensionFromMime(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".jpg";
        };
    }
    
    /**
     * 生成唯一文件名。
     * 
     * @param originalName 原始文件名（可选）
     * @param extension 文件扩展名
     * @return 唯一文件名
     */
    private String generateFileName(String originalName, String extension) {
        long timestamp = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        
        if (originalName != null && !originalName.isEmpty()) {
            // 提取原始文件名（不含扩展名）的前 20 个字符
            String baseName = originalName.replaceAll("\\.[^.]+$", "");
            baseName = baseName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (baseName.length() > 20) {
                baseName = baseName.substring(0, 20);
            }
            return timestamp + "_" + baseName + "_" + uuid + extension;
        }
        
        return timestamp + "_" + uuid + extension;
    }
}