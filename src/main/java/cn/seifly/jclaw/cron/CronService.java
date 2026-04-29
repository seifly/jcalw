package cn.seifly.jclaw.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.seifly.jclaw.logger.JClawLogger;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * 定时服务，调度和执行定时任务。
 * 
 * 这是 jclaw 定时任务系统的核心服务，负责任务的调度、执行和状态管理。
 * 现在使用 Quartz Scheduler 作为底层调度引擎，提供更可靠的调度能力。
 * 
 * 核心职责：
 * - 任务调度：使用 Quartz 解析 cron 表达式并调度任务
 * - 任务存储：持久化任务配置和状态信息到文件系统
 * - 任务执行：按时触发任务执行回调
 * - 状态管理：跟踪任务的启用、禁用状态和执行历史
 * - Misfire 处理：Quartz 内置的 misfire 处理机制，应对系统睡眠/关机
 * 
 * 技术实现：
 * - 使用 Quartz Scheduler 作为调度引擎
 * - 基于文件系统的 JSON 格式任务持久化
 * - ReentrantReadWriteLock 保护共享数据结构
 * - SecureRandom 生成唯一任务标识符
 * 
 * 调度类型：
 * - AT：一次性任务，指定时间执行（使用 SimpleTrigger）
 * - EVERY：周期性任务，按固定间隔执行（使用 SimpleTrigger）
 * - CRON：cron 表达式任务，按复杂规则执行（使用 CronTrigger）
 * 
 * Misfire 策略：
 * - AT：立即执行错过的任务（MISFIRE_INSTRUCTION_FIRE_NOW）
 * - EVERY：立即执行错过的任务并按原计划继续（MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT）
 * - CRON：立即执行一次错过的任务（MISFIRE_INSTRUCTION_FIRE_ONCE_NOW）
 * 
 * 使用场景：
 * 1. 为 CronTool 提供底层调度支持
 * 2. 系统级定时维护任务执行
 * 3. 第三方集成的定时任务需求
 * 4. 复杂业务逻辑的定时触发
 */
public class CronService {
    
    private static final JClawLogger logger = JClawLogger.getLogger("cron");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private static final int ID_BYTE_LENGTH = 8;
    private static final String HEX_FORMAT = "%02x";
    
    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";
    
    private static final long MAX_MISSED_TIME_MS = 24 * 60 * 60 * 1000L;
    
    private static final String JOB_GROUP = "JCLAW_JOBS";
    private static final String TRIGGER_GROUP = "JCLAW_TRIGGERS";
    
    private final String storePath;
    private CronStore store;
    private JobHandler onJob;
    private final ReentrantReadWriteLock lock;
    private volatile boolean running;
    
    private Scheduler scheduler;
    
    private final CronParser cronParser;
    
    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }
    
    public CronService(String storePath, JobHandler onJob) {
        this.storePath = storePath;
        this.onJob = onJob;
        this.lock = new ReentrantReadWriteLock();
        this.running = false;
        this.cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
        );
        loadStore();
    }
    
    public CronService(String storePath) {
        this(storePath, null);
    }
    
    public void start() {
        lock.writeLock().lock();
        try {
            if (running) {
                return;
            }
            
            loadStore();
            
            try {
                SchedulerFactory schedulerFactory = new StdSchedulerFactory();
                scheduler = schedulerFactory.getScheduler();
                
                scheduleExistingJobs();
                
                scheduler.start();
                running = true;
                
                logger.info("Cron service started (Quartz)", Map.of(
                        "enabled_jobs", store.getJobs().stream().filter(CronJob::isEnabled).count()
                ));
            } catch (SchedulerException e) {
                logger.error("Failed to start Quartz scheduler", Map.of(
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ), e);
                throw new RuntimeException("Failed to start Quartz scheduler", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void scheduleExistingJobs() throws SchedulerException {
        long now = System.currentTimeMillis();
        
        for (CronJob job : store.getJobs()) {
            if (!job.isEnabled()) {
                continue;
            }
            
            Long nextRun = job.getState().getNextRunAtMs();
            if (nextRun == null) {
                nextRun = computeNextRun(job.getSchedule(), now);
                job.getState().setNextRunAtMs(nextRun);
            }
            
            if (nextRun != null && nextRun < now) {
                long missedTime = now - nextRun;
                if (missedTime <= MAX_MISSED_TIME_MS) {
                    logger.info("检测到错过的任务（将由 Quartz misfire 处理）", Map.of(
                            "job_id", job.getId(),
                            "job_name", job.getName(),
                            "scheduled_at", formatTime(nextRun),
                            "missed_ms", missedTime
                    ));
                } else {
                    logger.warn("错过时间过长，跳过任务", Map.of(
                            "job_id", job.getId(),
                            "job_name", job.getName(),
                            "scheduled_at", formatTime(nextRun),
                            "missed_ms", missedTime,
                            "max_allowed_ms", MAX_MISSED_TIME_MS
                    ));
                    if (CronSchedule.ScheduleKind.AT == job.getSchedule().getKind()) {
                        if (job.isDeleteAfterRun()) {
                            store.getJobs().remove(job);
                        } else {
                            job.setEnabled(false);
                            job.getState().setNextRunAtMs(null);
                        }
                        continue;
                    } else {
                        nextRun = computeNextRun(job.getSchedule(), now);
                        job.getState().setNextRunAtMs(nextRun);
                    }
                }
            }
            
            if (job.isEnabled() && job.getState().getNextRunAtMs() != null) {
                scheduleJobInQuartz(job);
            }
        }
        
        saveStoreUnsafe();
    }
    
    private void scheduleJobInQuartz(CronJob job) throws SchedulerException {
        JobKey jobKey = new JobKey(job.getId(), JOB_GROUP);
        TriggerKey triggerKey = new TriggerKey(job.getId(), TRIGGER_GROUP);
        
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
        }
        
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(QuartzJobDelegate.JOB_ID_KEY, job.getId());
        jobDataMap.put(QuartzJobDelegate.CRON_SERVICE_KEY, this);
        
        JobDetail jobDetail = newJob(QuartzJobDelegate.class)
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .storeDurably()
                .build();
        
        Trigger trigger = buildTrigger(job, triggerKey);
        
        scheduler.scheduleJob(jobDetail, trigger);
        
        logger.debug("Scheduled job in Quartz", Map.of(
                "job_id", job.getId(),
                "job_name", job.getName(),
                "next_run", formatTime(job.getState().getNextRunAtMs())
        ));
    }
    
    private Trigger buildTrigger(CronJob job, TriggerKey triggerKey) {
        Long nextRunAtMs = job.getState().getNextRunAtMs();
        Date startTime = nextRunAtMs != null ? new Date(nextRunAtMs) : new Date();
        
        return switch (job.getSchedule().getKind()) {
            case AT -> buildAtTrigger(job, triggerKey, startTime);
            case EVERY -> buildEveryTrigger(job, triggerKey, startTime);
            case CRON -> buildCronTrigger(job, triggerKey);
        };
    }
    
    private Trigger buildAtTrigger(CronJob job, TriggerKey triggerKey, Date startTime) {
        return newTrigger()
                .withIdentity(triggerKey)
                .startAt(startTime)
                .withSchedule(simpleSchedule()
                        .withMisfireHandlingInstructionFireNow())
                .build();
    }
    
    private Trigger buildEveryTrigger(CronJob job, TriggerKey triggerKey, Date startTime) {
        long everyMs = job.getSchedule().getEveryMs() != null ? job.getSchedule().getEveryMs() : 60000L;
        
        return newTrigger()
                .withIdentity(triggerKey)
                .startAt(startTime)
                .withSchedule(simpleSchedule()
                        .withIntervalInMilliseconds(everyMs)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithExistingCount())
                .build();
    }
    
    private Trigger buildCronTrigger(CronJob job, TriggerKey triggerKey) {
        String cronExpr = job.getSchedule().getExpr();
        if (cronExpr == null || cronExpr.isEmpty()) {
            cronExpr = "0 0 * * * ?";
        }
        
        return newTrigger()
                .withIdentity(triggerKey)
                .withSchedule(cronSchedule(cronExpr)
                        .inTimeZone(TimeZone.getDefault())
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }
    
    public void stop() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return;
            }
            
            running = false;
            
            if (scheduler != null) {
                try {
                    scheduler.shutdown();
                    logger.info("Quartz scheduler shutdown");
                } catch (SchedulerException e) {
                    logger.error("Error shutting down Quartz scheduler", Map.of(
                            "error_type", e.getClass().getSimpleName(),
                            "error_message", e.getMessage()
                    ), e);
                }
            }
            
            logger.info("Cron service stopped");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void executeQuartzJob(String jobId) {
        lock.writeLock().lock();
        try {
            CronJob job = findJobById(jobId);
            if (job == null) {
                logger.warn("Job not found for execution", Map.of("job_id", jobId));
                return;
            }
            
            if (!job.isEnabled()) {
                logger.debug("Job is disabled, skipping execution", Map.of("job_id", jobId));
                return;
            }
            
            long startTime = System.currentTimeMillis();
            String error = invokeJobHandler(job);
            
            updateJobState(job, startTime, error);
            
            if (CronSchedule.ScheduleKind.AT == job.getSchedule().getKind()) {
                unscheduleJob(jobId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void unscheduleJob(String jobId) {
        try {
            JobKey jobKey = new JobKey(jobId, JOB_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                logger.debug("Unscheduled job from Quartz", Map.of("job_id", jobId));
            }
        } catch (SchedulerException e) {
            logger.error("Error unscheduling job", Map.of(
                    "job_id", jobId,
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ), e);
        }
    }
    
    private String invokeJobHandler(CronJob job) {
        try {
            if (onJob != null) {
                onJob.handle(job);
            }
            return null;
        } catch (Exception e) {
            String error = e.getMessage();
            logger.error("任务执行失败", Map.of(
                    "job_id", job.getId(),
                    "job_name", job.getName(),
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", error
            ), e);
            return error;
        }
    }
    
    private void updateJobState(CronJob job, long startTime, String error) {
        CronJob storeJob = findJobById(job.getId());
        if (storeJob == null) {
            return;
        }
        
        storeJob.getState().setLastRunAtMs(startTime);
        storeJob.setUpdatedAtMs(System.currentTimeMillis());
        
        if (error != null) {
            storeJob.getState().setLastStatus(STATUS_ERROR);
            storeJob.getState().setLastError(error);
        } else {
            storeJob.getState().setLastStatus(STATUS_OK);
            storeJob.getState().setLastError(null);
        }
        
        handlePostExecution(storeJob);
        saveStoreUnsafe();
    }
    
    private CronJob findJobById(String jobId) {
        for (CronJob j : store.getJobs()) {
            if (j.getId().equals(jobId)) {
                return j;
            }
        }
        return null;
    }
    
    private void handlePostExecution(CronJob job) {
        if (CronSchedule.ScheduleKind.AT == job.getSchedule().getKind()) {
            if (job.isDeleteAfterRun()) {
                removeJobUnsafe(job.getId());
            } else {
                job.setEnabled(false);
                job.getState().setNextRunAtMs(null);
            }
        } else {
            Long nextRun = computeNextRun(job.getSchedule(), System.currentTimeMillis());
            job.getState().setNextRunAtMs(nextRun);
            
            try {
                rescheduleJobInQuartz(job);
            } catch (SchedulerException e) {
                logger.error("Error rescheduling job", Map.of(
                        "job_id", job.getId(),
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ), e);
            }
        }
    }
    
    private void rescheduleJobInQuartz(CronJob job) throws SchedulerException {
        TriggerKey triggerKey = new TriggerKey(job.getId(), TRIGGER_GROUP);
        
        if (scheduler.checkExists(triggerKey)) {
            Trigger newTrigger = buildTrigger(job, triggerKey);
            scheduler.rescheduleJob(triggerKey, newTrigger);
            
            logger.debug("Rescheduled job in Quartz", Map.of(
                    "job_id", job.getId(),
                    "next_run", formatTime(job.getState().getNextRunAtMs())
            ));
        }
    }
    
    private Long computeNextRun(CronSchedule schedule, long nowMs) {
        return switch (schedule.getKind()) {
            case AT -> computeAtNextRun(schedule, nowMs);
            case EVERY -> computeEveryNextRun(schedule, nowMs);
            case CRON -> computeCronNextRun(schedule, nowMs);
        };
    }
    
    private Long computeAtNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getAtMs() != null && schedule.getAtMs() > nowMs) {
            return schedule.getAtMs();
        }
        return null;
    }
    
    private Long computeEveryNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getEveryMs() == null || schedule.getEveryMs() <= 0) {
            return null;
        }
        return nowMs + schedule.getEveryMs();
    }
    
    private Long computeCronNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getExpr() == null || schedule.getExpr().isEmpty()) {
            return null;
        }
        
        try {
            Cron cron = cronParser.parse(schedule.getExpr());
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(nowMs), 
                ZoneId.systemDefault()
            );
            Optional<ZonedDateTime> next = executionTime.nextExecution(now);
            
            return next.map(zonedDateTime -> zonedDateTime.toInstant().toEpochMilli())
                       .orElse(null);
        } catch (Exception e) {
            logger.error("计算 Cron 表达式下次执行时间失败", Map.of(
                    "expr", schedule.getExpr(),
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ), e);
            return null;
        }
    }
    
    private String formatTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toString();
    }
    
    private void loadStore() {
        store = new CronStore();
        
        try {
            Path path = Paths.get(storePath);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                store = objectMapper.readValue(json, CronStore.class);
                if (store.getJobs() == null) {
                    store.setJobs(new ArrayList<>());
                }
            }
        } catch (Exception e) {
            logger.warn("加载定时任务存储失败，使用空存储", Map.of(
                    "store_path", storePath,
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ));
            logger.error("加载定时任务存储异常详情", Map.of(
                    "store_path", storePath
            ), e);
            store = new CronStore();
        }
    }
    
    private void saveStoreUnsafe() {
        try {
            Path path = Paths.get(storePath);
            Files.createDirectories(path.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            Files.writeString(path, json);
        } catch (Exception e) {
            logger.error("保存定时任务存储失败", Map.of(
                    "store_path", storePath,
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ), e);
        }
    }
    
    public CronJob addJob(String name, CronSchedule schedule, String message,
                          String channel, String to) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = CronSchedule.ScheduleKind.AT == schedule.getKind();
            
            CronJob job = createJob(name, schedule, message, channel, to, now, deleteAfterRun);
            
            store.getJobs().add(job);
            saveStoreUnsafe();
            
            if (running && job.isEnabled()) {
                try {
                    scheduleJobInQuartz(job);
                } catch (SchedulerException e) {
                    logger.error("Error scheduling new job", Map.of(
                            "job_id", job.getId(),
                            "error_type", e.getClass().getSimpleName(),
                            "error_message", e.getMessage()
                    ), e);
                }
            }
            
            logger.info("Added cron job", Map.of(
                    "job_id", job.getId(),
                    "name", name,
                    "kind", schedule.getKind()
            ));
            
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private CronJob createJob(String name, CronSchedule schedule, String message,
                             String channel, String to,
                             long now, boolean deleteAfterRun) {
        CronJob job = new CronJob();
        job.setId(generateId());
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);
        job.setPayload(new CronPayload(message, channel, to));
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);
        job.getState().setNextRunAtMs(computeNextRun(schedule, now));
        return job;
    }
    
    public boolean removeJob(String jobId) {
        lock.writeLock().lock();
        try {
            return removeJobUnsafe(jobId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private boolean removeJobUnsafe(String jobId) {
        if (running) {
            unscheduleJob(jobId);
        }
        
        boolean removed = store.getJobs().removeIf(j -> j.getId().equals(jobId));
        if (removed) {
            saveStoreUnsafe();
            logger.info("Removed cron job", Map.of("job_id", jobId));
        }
        return removed;
    }
    
    public CronJob enableJob(String jobId, boolean enabled) {
        lock.writeLock().lock();
        try {
            CronJob job = findJobById(jobId);
            if (job == null) {
                return null;
            }
            
            boolean wasEnabled = job.isEnabled();
            job.setEnabled(enabled);
            job.setUpdatedAtMs(System.currentTimeMillis());
            
            if (enabled && !wasEnabled) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), System.currentTimeMillis()));
                
                if (running) {
                    try {
                        scheduleJobInQuartz(job);
                    } catch (SchedulerException e) {
                        logger.error("Error scheduling job after enable", Map.of(
                                "job_id", jobId,
                                "error_type", e.getClass().getSimpleName(),
                                "error_message", e.getMessage()
                        ), e);
                    }
                }
            } else if (!enabled && wasEnabled) {
                job.getState().setNextRunAtMs(null);
                
                if (running) {
                    unscheduleJob(jobId);
                }
            }
            
            saveStoreUnsafe();
            
            logger.info((enabled ? "Enabled" : "Disabled") + " cron job", Map.of(
                    "job_id", jobId,
                    "job_name", job.getName()
            ));
            
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public List<CronJob> listJobs(boolean includeDisabled) {
        lock.readLock().lock();
        try {
            if (includeDisabled) {
                return new ArrayList<>(store.getJobs());
            }
            
            return store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public Map<String, Object> status() {
        lock.readLock().lock();
        try {
            long enabledCount = store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .count();
            
            Map<String, Object> status = new HashMap<>();
            status.put("enabled", running);
            status.put("jobs", store.getJobs().size());
            status.put("enabled_jobs", enabledCount);
            status.put("scheduler", "quartz");
            return status;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public void setOnJob(JobHandler handler) {
        this.onJob = handler;
    }
    
    public void load() {
        lock.writeLock().lock();
        try {
            loadStore();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private String generateId() {
        byte[] bytes = new byte[ID_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(HEX_FORMAT, b));
        }
        return sb.toString();
    }
    
    public boolean isRunning() { 
        return running; 
    }
}
