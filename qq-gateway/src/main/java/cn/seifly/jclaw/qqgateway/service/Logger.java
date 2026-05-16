package cn.seifly.jclaw.qqgateway.service;

/**
 * 简化日志工具
 */
public class Logger {
    
    private final String name;
    
    public Logger(String name) {
        this.name = name;
    }
    
    public static Logger getLogger(String name) {
        return new Logger(name);
    }
    
    private String format(String level, String msg, Object... args) {
        String formatted = args.length > 0 ? String.format(msg, args) : msg;
        return String.format("[%s] [%s] %s", getCurrentTime(), level, formatted);
    }
    
    private String getCurrentTime() {
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
    
    public void info(String msg, Object... args) {
        System.out.println(format("INFO", msg, args));
    }
    
    public void debug(String msg, Object... args) {
        System.out.println(format("DEBUG", msg, args));
    }
    
    public void warn(String msg, Object... args) {
        System.out.println(format("WARN", msg, args));
    }
    
    public void error(String msg, Object... args) {
        System.err.println(format("ERROR", msg, args));
    }
    
    public void error(String msg, Throwable t) {
        System.err.println(format("ERROR", msg));
        t.printStackTrace();
    }
}
