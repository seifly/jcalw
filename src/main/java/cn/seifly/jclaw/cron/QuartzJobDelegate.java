package cn.seifly.jclaw.cron;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz Job 委托类，将 Quartz 的 Job 执行委托给 CronService。
 * 
 * 这个类作为 Quartz 调度系统和 jclaw 任务系统之间的桥梁。
 * 它实现了 Quartz 的 Job 接口，从 JobDataMap 中获取任务 ID，
 * 然后通过 CronService 执行实际的任务逻辑。
 * 
 * 设计要点：
 * - 无状态设计：每次执行都是独立的
 * - 通过 JobDataMap 传递上下文信息
 * - 支持任务删除/禁用的检查
 */
public class QuartzJobDelegate implements Job {
    
    private static final Logger logger = LoggerFactory.getLogger(QuartzJobDelegate.class);
    
    /**
     * JobDataMap 中存储任务 ID 的键名
     */
    public static final String JOB_ID_KEY = "jobId";
    
    /**
     * JobDataMap 中存储 CronService 引用的键名
     */
    public static final String CRON_SERVICE_KEY = "cronService";
    
    /**
     * 执行任务。
     * 
     * Quartz 调度器在触发时间到达时调用此方法。
     * 它从 JobDataMap 中获取任务 ID 和 CronService 引用，
     * 然后调用 CronService 执行实际的任务。
     * 
     * @param context 任务执行上下文，包含 JobDataMap
     * @throws JobExecutionException 如果执行失败
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String jobId = context.getMergedJobDataMap().getString(JOB_ID_KEY);
        Object cronServiceObj = context.getMergedJobDataMap().get(CRON_SERVICE_KEY);
        
        if (jobId == null || cronServiceObj == null) {
            logger.error("Quartz job missing required data: jobId={}, cronService={}", jobId, cronServiceObj);
            return;
        }
        
        if (!(cronServiceObj instanceof CronService)) {
            logger.error("Invalid CronService type: {}", cronServiceObj.getClass().getName());
            return;
        }
        
        CronService cronService = (CronService) cronServiceObj;
        
        try {
            logger.debug("Quartz triggering job: {}", jobId);
            cronService.executeQuartzJob(jobId);
        } catch (Exception e) {
            logger.error("Quartz job execution failed: {} - {}", jobId, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
