package cn.seifly.jclaw.skills;

import cn.seifly.jclaw.logger.JClawLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 技能加载器 - 加载和管理 Agent 技能
 * <p>
 * 从多个来源加载技能文件，支持工作空间、全局和内置三个层级。
 * 技能文件采用 Markdown 格式，通过 YAML 前置元数据定义名称和描述。
 * <p>
 * 加载优先级：workspace > global > builtin
 * <p>
 * 内置技能加载逻辑：
 * 1. 如果提供了 builtinSkills 目录路径，则优先从该目录加载
 * 2. 否则，从 classpath 加载硬编码的技能列表
 */
public class SkillsLoader {

    private static final JClawLogger logger = JClawLogger.getLogger("skills");

    /**
     * 工作空间根路径
     */
    private final String workspace;

    /**
     * 工作空间技能目录路径（workspace/skills）
     */
    private final String workspaceSkills;

    /**
     * 全局技能目录路径
     */
    private final String globalSkills;

    /**
     * 内置技能目录路径（可选，用于从文件系统加载内置技能）
     */
    private final String builtinSkills;

    /**
     * 内置技能名称列表（从 classpath 加载）
     */
    private static final List<String> BUILTIN_SKILL_NAMES = Arrays.asList(
            "weather", "github", "skill-creator", "tmux"
    );

    /**
     * classpath 中内置技能的基础路径
     */
    private static final String BUILTIN_SKILLS_PATH = "skills/";

    /**
     * 构造 SkillsLoader 实例
     *
     * @param workspace     工作空间根路径
     * @param globalSkills  全局技能目录路径
     * @param builtinSkills 内置技能目录路径（可选，用于从文件系统加载内置技能）
     */
    public SkillsLoader(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.workspaceSkills = Paths.get(workspace, "skills").toString();
        this.globalSkills = globalSkills;
        this.builtinSkills = builtinSkills;
        
        logger.info("SkillsLoader 初始化", Map.of(
                "workspace", workspace,
                "global_skills", globalSkills != null ? globalSkills : "null",
                "builtin_skills", builtinSkills != null ? builtinSkills : "null"
        ));
    }

    /**
     * 列出所有可用的技能
     * <p>
     * 按优先级顺序加载，同名技能高优先级覆盖低优先级
     */
    public List<SkillInfo> listSkills() {
        List<SkillInfo> skills = new ArrayList<>();

        // 工作空间技能（最高优先级）
        if (workspaceSkills != null) {
            int beforeCount = skills.size();
            addSkillsFromDir(skills, workspaceSkills, "workspace");
            logger.debug("加载工作空间技能", Map.of(
                    "directory", workspaceSkills,
                    "loaded_count", skills.size() - beforeCount
            ));
        }

        // 全局技能
        if (globalSkills != null) {
            int beforeCount = skills.size();
            addSkillsFromDir(skills, globalSkills, "global");
            logger.debug("加载全局技能", Map.of(
                    "directory", globalSkills,
                    "loaded_count", skills.size() - beforeCount
            ));
        }

        // 内置技能
        int beforeCount = skills.size();
        addBuiltinSkills(skills);
        logger.debug("加载内置技能", Map.of(
                "loaded_count", skills.size() - beforeCount,
                "source", builtinSkills != null ? "filesystem: " + builtinSkills : "classpath"
        ));

        logger.info("技能加载完成", Map.of(
                "total_count", skills.size(),
                "skill_names", skills.stream().map(SkillInfo::getName).collect(Collectors.joining(", "))
        ));

        return skills;
    }

    /**
     * 添加内置技能
     * <p>
     * 加载逻辑：
     * 1. 如果提供了 builtinSkills 目录路径，则从该目录扫描并加载所有技能
     * 2. 否则，从 classpath 加载硬编码的技能列表
     *
     * @param skills 技能列表
     */
    private void addBuiltinSkills(List<SkillInfo> skills) {
        // 如果提供了 builtinSkills 目录路径，则优先从该目录加载
        if (builtinSkills != null && !builtinSkills.isEmpty()) {
            Path dir = Paths.get(builtinSkills);
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                logger.info("从文件系统加载内置技能", Map.of("directory", builtinSkills));
                addSkillsFromDir(skills, builtinSkills, "builtin");
                return;
            } else {
                logger.warn("内置技能目录不存在，将从 classpath 加载", Map.of("directory", builtinSkills));
            }
        }

        // 从 classpath 加载硬编码的技能列表（回退方案）
        logger.info("从 classpath 加载内置技能");
        for (String skillName : BUILTIN_SKILL_NAMES) {
            // 检查是否已存在更高优先级的同名技能
            boolean exists = skills.stream()
                    .anyMatch(s -> s.getName().equals(skillName));

            if (!exists) {
                String content = loadBuiltinSkillContent(skillName);
                if (content != null) {
                    SkillInfo info = new SkillInfo();
                    info.setName(skillName);
                    info.setPath("classpath:" + BUILTIN_SKILLS_PATH + skillName + "/SKILL.md");
                    info.setSource("builtin");

                    // 解析元数据
                    String frontmatter = extractFrontmatter(content);
                    if (frontmatter != null && !frontmatter.isEmpty()) {
                        Map<String, String> yaml = parseSimpleYAML(frontmatter);
                        info.setDescription(yaml.getOrDefault("description", ""));
                    }

                    skills.add(info);
                    logger.debug("从 classpath 加载技能", Map.of("skill_name", skillName));
                } else {
                    logger.warn("无法从 classpath 加载技能", Map.of("skill_name", skillName));
                }
            }
        }
    }

    /**
     * 从 classpath 加载内置技能内容
     *
     * @param skillName 技能名称
     * @return 技能内容，失败时返回 null
     */
    private String loadBuiltinSkillContent(String skillName) {
        String resourcePath = BUILTIN_SKILLS_PATH + skillName + "/SKILL.md";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从指定目录添加技能到列表
     */
    private void addSkillsFromDir(List<SkillInfo> skills, String dirPath, String source) {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;

        try {
            Files.list(dir).filter(Files::isDirectory).forEach(skillDir -> {
                String name = skillDir.getFileName().toString();
                Path skillFile = skillDir.resolve("SKILL.md");

                if (Files.exists(skillFile)) {
                    // 检查是否已存在更高优先级的同名技能
                    boolean exists = skills.stream()
                            .anyMatch(s -> s.getName().equals(name) &&
                                    (source.equals("builtin") ||
                                            (source.equals("global") && s.getSource().equals("workspace"))));

                    if (!exists) {
                        SkillInfo info = new SkillInfo();
                        info.setName(name);
                        info.setPath(skillFile.toString());
                        info.setSource(source);

                        // 加载描述
                        String description = parseSkillDescription(skillFile);
                        if (description != null) {
                            info.setDescription(description);
                        }

                        skills.add(info);
                    }
                }
            });
        } catch (IOException e) {
            // 忽略读取错误
        }
    }

    /**
     * 按名称加载技能，返回去除 YAML 前置元数据后的内容
     */
    public String loadSkill(String name) {
        logger.debug("加载技能", Map.of("skill_name", name));

        // 优先尝试工作空间技能
        String content = loadSkillFromDir(workspaceSkills, name);
        if (content != null) {
            logger.debug("从工作空间加载技能", Map.of("skill_name", name, "directory", workspaceSkills));
            return content;
        }

        // 尝试全局技能
        content = loadSkillFromDir(globalSkills, name);
        if (content != null) {
            logger.debug("从全局目录加载技能", Map.of("skill_name", name, "directory", globalSkills));
            return content;
        }

        // 尝试从 builtinSkills 目录加载（如果提供了）
        content = loadSkillFromDir(builtinSkills, name);
        if (content != null) {
            logger.debug("从内置技能目录加载技能", Map.of("skill_name", name, "directory", builtinSkills));
            return content;
        }

        // 尝试内置技能（从 classpath 加载）
        content = loadBuiltinSkillContent(name);
        if (content != null) {
            logger.debug("从 classpath 加载技能", Map.of("skill_name", name));
            return stripFrontmatter(content);
        }

        logger.warn("无法加载技能", Map.of("skill_name", name));
        return null;
    }

    /**
     * 从指定目录加载技能
     */
    private String loadSkillFromDir(String dir, String name) {
        if (dir == null) return null;

        Path skillFile = Paths.get(dir, name, "SKILL.md");
        if (Files.exists(skillFile)) {
            try {
                String content = Files.readString(skillFile);
                return stripFrontmatter(content);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 构建技能摘要（XML 格式）
     */
    public String buildSkillsSummary() {
        List<SkillInfo> allSkills = listSkills();
        if (allSkills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<skills>\n");

        for (SkillInfo s : allSkills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXML(s.getName())).append("</name>\n");
            sb.append("    <description>").append(escapeXML(s.getDescription())).append("</description>\n");
            sb.append("    <location>").append(escapeXML(s.getPath())).append("</location>\n");
            sb.append("    <source>").append(s.getSource()).append("</source>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</skills>");
        return sb.toString();
    }

    /**
     * 从 SKILL.md 的 YAML 前置元数据中解析描述信息
     */
    private String parseSkillDescription(Path skillPath) {
        try {
            String content = Files.readString(skillPath);
            String frontmatter = extractFrontmatter(content);
            if (frontmatter == null || frontmatter.isEmpty()) {
                return "";
            }
            Map<String, String> yaml = parseSimpleYAML(frontmatter);
            return yaml.getOrDefault("description", "");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 提取 YAML 前置元数据（--- 分隔符之间的内容）
     */
    private String extractFrontmatter(String content) {
        Pattern pattern = Pattern.compile("(?s)^---\n(.*)\n---");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * 去除 YAML 前置元数据
     */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("^---\n.*?\n---\n", "");
    }

    /**
     * 解析简单的 YAML 格式（key: value）
     */
    private Map<String, String> parseSimpleYAML(String content) {
        Map<String, String> result = new HashMap<>();

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                // 移除引号
                value = value.replaceAll("^['\"]|['\"]$", "");
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * 将 builtin 技能从 classpath 解压到文件系统缓存目录。
     * <p>
     * 当 builtin 技能被 invoke 时，需要一个真实的文件系统路径来执行脚本。
     * 此方法的处理逻辑：
     * 1. 如果技能是从 builtinSkills 目录加载的（文件系统路径），直接返回该目录路径
     * 2. 否则，将 classpath 中的技能资源复制到 workspace/.builtin-cache/{skillName}/ 目录下
     *
     * @param skillName 技能名称
     * @return 解压后的文件系统路径，失败时返回 null
     */
    public String extractBuiltinSkillToFileSystem(String skillName) {
        logger.info("解压内置技能到文件系统", Map.of("skill_name", skillName));

        // 首先检查是否从 builtinSkills 目录加载（如果提供了）
        if (builtinSkills != null && !builtinSkills.isEmpty()) {
            Path skillDir = Paths.get(builtinSkills, skillName);
            Path skillFile = skillDir.resolve("SKILL.md");
            if (Files.exists(skillFile)) {
                logger.info("技能已在文件系统中，直接返回路径", Map.of(
                        "skill_name", skillName,
                        "path", skillDir.toAbsolutePath().toString()
                ));
                return skillDir.toAbsolutePath().toString();
            }
        }

        // 检查是否在硬编码的内置技能列表中
        if (!BUILTIN_SKILL_NAMES.contains(skillName)) {
            logger.warn("技能不在内置技能列表中", Map.of("skill_name", skillName));
            return null;
        }

        Path cacheDir = Paths.get(workspace, ".builtin-cache", skillName);
        Path cachedSkillFile = cacheDir.resolve("SKILL.md");

        // 如果缓存已存在，直接返回
        if (Files.exists(cachedSkillFile)) {
            logger.info("技能缓存已存在", Map.of("skill_name", skillName, "cache_dir", cacheDir.toString()));
            return cacheDir.toAbsolutePath().toString();
        }

        // 从 classpath 解压到缓存目录
        String resourceBase = BUILTIN_SKILLS_PATH + skillName + "/";
        try {
            Files.createDirectories(cacheDir);

            // 复制 SKILL.md
            String skillContent = loadBuiltinSkillContent(skillName);
            if (skillContent == null) {
                logger.warn("无法从 classpath 加载技能内容", Map.of("skill_name", skillName));
                return null;
            }
            Files.writeString(cachedSkillFile, skillContent);

            // 尝试复制技能目录下的其他资源（scripts/、references/、assets/）
            for (String subdir : new String[]{"scripts", "references", "assets"}) {
                extractBuiltinSubdirectory(resourceBase + subdir + "/", cacheDir.resolve(subdir));
            }

            logger.info("技能已从 classpath 解压到缓存目录", Map.of(
                    "skill_name", skillName,
                    "cache_dir", cacheDir.toAbsolutePath().toString()
            ));

            return cacheDir.toAbsolutePath().toString();
        } catch (IOException e) {
            logger.error("解压技能失败", Map.of("skill_name", skillName, "error", e.getMessage()));
            return null;
        }
    }

    /**
     * 尝试从 classpath 解压子目录资源。
     *
     * @param resourcePath classpath 中的资源路径前缀
     * @param targetDir    目标文件系统目录
     */
    private void extractBuiltinSubdirectory(String resourcePath, Path targetDir) {
        // classpath 资源无法直接列举目录内容，这里通过尝试加载已知文件来处理
        // 对于更复杂的场景，可以在 SKILL.md 中声明附带的文件列表
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            // 如果子目录资源存在（某些类加载器支持目录流），尝试读取
            if (is != null) {
                Files.createDirectories(targetDir);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String fileName;
                    while ((fileName = reader.readLine()) != null) {
                        fileName = fileName.trim();
                        if (fileName.isEmpty()) continue;
                        try (InputStream fileIs = getClass().getClassLoader()
                                .getResourceAsStream(resourcePath + fileName)) {
                            if (fileIs != null) {
                                Files.copy(fileIs, targetDir.resolve(fileName));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 子目录不存在或无法读取，忽略
        }
    }

    /**
     * 删除工作空间技能（删除对应目录）
     *
     * @param name 技能名称
     * @return true 表示成功，false 表示技能不存在或非工作空间技能
     */
    public boolean deleteWorkspaceSkill(String name) {
        if (workspaceSkills == null) return false;
        Path skillDir = Paths.get(workspaceSkills, name);
        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) return false;
        try {
            deleteDirectoryRecursively(skillDir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * 保存（新建或更新）工作空间技能内容
     *
     * @param name    技能名称
     * @param content 技能文件完整内容
     * @return true 表示成功
     */
    public boolean saveWorkspaceSkill(String name, String content) {
        if (workspaceSkills == null) return false;
        Path skillDir = Paths.get(workspaceSkills, name);
        Path skillFile = skillDir.resolve("SKILL.md");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 转义 XML 特殊字符
     */
    private String escapeXML(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
