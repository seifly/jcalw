package cn.seifly.jclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * jclaw Spring Boot 应用主类
 * <p>
 * 这个类作为 Spring Boot 应用的入口点，同时保持与原有命令行界面的兼容性。
 * 支持两种运行模式：
 * 1. Web 模式：作为 Spring Boot Web 应用运行，提供 REST API 和 Web 控制台
 * 2. CLI 模式：保持原有的命令行功能
 * <p>
 * 使用示例：
 * java -jar jclaw.jar                # 以 Web 模式启动
 * java -jar jclaw.jar agent          # 以 CLI 模式运行 agent 命令
 * java -jar jclaw.jar gateway        # 以 CLI 模式运行 gateway 命令
 */
@SpringBootApplication
public class JClawApplication {

    /**
     * 当前软件版本号
     */
    public static final String VERSION = "0.1.0";

    /**
     * 应用程序 Logo 符号
     */
    public static final String LOGO = "🦞";


    /**
     * 应用程序主入口
     * <p>
     * 根据命令行参数决定运行模式：
     * - 如果没有参数或参数以 -- 开头，以 Web 模式启动
     * - 否则以 CLI 模式运行相应命令
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(JClawApplication.class, args);

    }


}