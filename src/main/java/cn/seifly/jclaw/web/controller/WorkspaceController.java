package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作空间文件 API 控制器
 * 
 * 提供工作空间文件的列表、读取和保存功能。
 */
@RestController
@RequestMapping("/api/workspace/files")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
public class WorkspaceController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    /** 预定义的工作空间文件列表及其描述 */
    private static final Map<String, String> WORKSPACE_FILES_INFO = new HashMap<>();
    
    static {
        WORKSPACE_FILES_INFO.put("AGENTS.md", "Agent 行为指令 - 定义 Agent 如何执行任务、协作规则和行为约束");
        WORKSPACE_FILES_INFO.put("SOUL.md", "Agent 个性与价值观 - 定义 Agent 的性格特点、价值观和响应风格");
        WORKSPACE_FILES_INFO.put("USER.md", "用户画像与偏好 - 记录用户的个人信息、使用习惯和偏好设置");
        WORKSPACE_FILES_INFO.put("IDENTITY.md", "Agent 身份描述 - 定义 Agent 的基本身份、角色定位和专业领域");
        WORKSPACE_FILES_INFO.put("PROFILE.md", "用户个人资料 - 详细的用户个人信息和背景资料");
        WORKSPACE_FILES_INFO.put("HEARTBEAT.md", "心跳配置 - 定时任务和周期性行为的配置说明");
    }
    
    /** 预定义的工作空间文件列表 */
    private static final String[] WORKSPACE_FILES = {
        "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md", "PROFILE.md", "HEARTBEAT.md"
    };
    
    /** memory 子目录和文件名 */
    private static final String MEMORY_SUBDIR = "memory";
    private static final String MEMORY_FILE = "MEMORY.md";
    
    @Autowired
    private Config config;
    
    /**
     * 获取工作空间中预定义的文件列表以及 memory 文件（如果存在）
     * 
     * 不存在的文件不包含在结果中。
     * 
     * @return 文件列表，包含 name、exists、size、lastModified
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listWorkspaceFiles() {
        String workspace = config.getWorkspacePath();
        List<Map<String, Object>> files = new ArrayList<>();
        
        for (String fileName : WORKSPACE_FILES) {
            Path filePath = Paths.get(workspace, fileName);
            if (Files.exists(filePath)) {
                files.add(createFileInfo(fileName, filePath));
            }
        }
        
        addMemoryFile(files, workspace);
        
        return ResponseEntity.ok(files);
    }
    
    /**
     * 获取所有预定义的工作空间文件信息（包括不存在的文件）
     * 
     * 返回所有预定义文件，无论文件是否存在。
     * 
     * @return 文件列表，包含 name、exists、size、lastModified、description
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> listAllWorkspaceFiles() {
        String workspace = config.getWorkspacePath();
        List<Map<String, Object>> files = new ArrayList<>();
        
        for (String fileName : WORKSPACE_FILES) {
            Path filePath = Paths.get(workspace, fileName);
            boolean exists = Files.exists(filePath);
            
            Map<String, Object> file = new HashMap<>();
            file.put("name", fileName);
            file.put("exists", exists);
            file.put("description", WORKSPACE_FILES_INFO.getOrDefault(fileName, ""));
            
            if (exists) {
                try {
                    file.put("size", Files.size(filePath));
                    file.put("lastModified", Files.getLastModifiedTime(filePath).toMillis());
                } catch (Exception e) {
                    file.put("size", 0);
                    file.put("lastModified", 0);
                }
            } else {
                file.put("size", 0);
                file.put("lastModified", 0);
            }
            
            files.add(file);
        }
        
        return ResponseEntity.ok(files);
    }
    
    /**
     * 读取工作空间中指定文件的内容
     * 
     * @param fileName 文件名（URL 编码）
     * @return 文件内容
     */
    @GetMapping("/{*fileName}")
    public ResponseEntity<Map<String, Object>> getWorkspaceFile(@PathVariable("fileName") String fileName) {
        try {
            // 处理路径：Spring 的 {*fileName} 会包含前导斜杠，需要去掉
            if (fileName.startsWith("/")) {
                fileName = fileName.substring(1);
            }
            
            String decodedFileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            String workspace = config.getWorkspacePath();
            Path filePath = Paths.get(workspace, decodedFileName);
            
            if (Files.exists(filePath)) {
                String content = Files.readString(filePath);
                
                Map<String, Object> result = new HashMap<>();
                result.put("name", decodedFileName);
                result.put("content", content);
                
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "File not found");
                return ResponseEntity.status(404).body(error);
            }
            
        } catch (Exception e) {
            logger.error("Workspace API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 保存工作空间中的指定文件
     * 
     * 必要时自动创建父目录。
     * 
     * @param fileName 文件名（URL 编码）
     * @param request 包含 content 的请求体
     * @return 保存结果
     */
    @PutMapping("/{*fileName}")
    public ResponseEntity<Map<String, Object>> saveWorkspaceFile(
            @PathVariable("fileName") String fileName,
            @RequestBody Map<String, Object> request) {
        
        try {
            // 处理路径：Spring 的 {*fileName} 会包含前导斜杠，需要去掉
            if (fileName.startsWith("/")) {
                fileName = fileName.substring(1);
            }
            
            String decodedFileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            String workspace = config.getWorkspacePath();
            
            String content = (String) request.getOrDefault("content", "");
            Path filePath = Paths.get(workspace, decodedFileName);
            
            // 确保父目录存在
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            
            Files.writeString(filePath, content);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "File saved");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Workspace API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 构建包含 name、exists、size、lastModified 的文件信息 Map。
     * 读取属性失败时以 0 填充。
     */
    private Map<String, Object> createFileInfo(String fileName, Path filePath) {
        Map<String, Object> file = new HashMap<>();
        file.put("name", fileName);
        file.put("exists", true);
        
        try {
            file.put("size", Files.size(filePath));
            file.put("lastModified", Files.getLastModifiedTime(filePath).toMillis());
        } catch (Exception e) {
            file.put("size", 0);
            file.put("lastModified", 0);
        }
        
        return file;
    }
    
    /**
     * 检测并将 memory 子目录下的文件（如果存在）追加到文件列表。
     */
    private void addMemoryFile(List<Map<String, Object>> files, String workspace) {
        Path memoryFile = Paths.get(workspace, MEMORY_SUBDIR, MEMORY_FILE);
        
        if (Files.exists(memoryFile)) {
            String memoryFileName = MEMORY_SUBDIR + "/" + MEMORY_FILE;
            files.add(createFileInfo(memoryFileName, memoryFile));
        }
    }
}