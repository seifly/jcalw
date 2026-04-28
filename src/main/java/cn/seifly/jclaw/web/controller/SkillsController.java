package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.skills.SkillInfo;
import cn.seifly.jclaw.skills.SkillsLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理 API 控制器
 * 
 * 提供技能的查询、创建、更新和删除功能。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class SkillsController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    @Autowired
    private SkillsLoader skillsLoader;
    
    /**
     * 获取所有技能列表
     * 
     * @return 技能列表，包含 name、description、source、path
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<SkillInfo> skills = skillsLoader.listSkills();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (SkillInfo skill : skills) {
            Map<String, Object> skillNode = new HashMap<>();
            skillNode.put("name", skill.getName());
            skillNode.put("description", skill.getDescription() != null ? skill.getDescription() : "");
            skillNode.put("source", skill.getSource());
            skillNode.put("path", skill.getPath());
            result.add(skillNode);
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取指定技能的内容
     * 
     * @param name 技能名称（URL 编码）
     * @return 技能内容
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable("name") String name) {
        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            String content = skillsLoader.loadSkill(decodedName);
            
            if (content != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("name", decodedName);
                result.put("content", content);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Skill not found");
                return ResponseEntity.status(404).body(error);
            }
        } catch (Exception e) {
            logger.error("Skills API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 保存或更新技能
     * 
     * @param name 技能名称（URL 编码）
     * @param request 包含 content 的请求体
     * @return 保存结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> saveSkill(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {
        
        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            String content = request.containsKey("content") ? (String) request.get("content") : null;
            
            if (content == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing content");
                return ResponseEntity.status(400).body(error);
            }
            
            if (skillsLoader.saveWorkspaceSkill(decodedName, content)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Failed to save skill");
                return ResponseEntity.status(500).body(error);
            }
        } catch (Exception e) {
            logger.error("Skills API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 删除技能
     * 
     * 注意：只能删除 workspace 中的技能，不能删除内置技能。
     * 
     * @param name 技能名称（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable("name") String name) {
        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            
            if (skillsLoader.deleteWorkspaceSkill(decodedName)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Skill not found or not a workspace skill");
                return ResponseEntity.status(404).body(error);
            }
        } catch (Exception e) {
            logger.error("Skills API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}