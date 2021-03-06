package com.huaweicloud.dis.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.MoreExecutors;
import com.huaweicloud.dis.agent.config.AgentConfiguration;
import com.huaweicloud.dis.agent.config.AgentOptions;
import com.huaweicloud.dis.agent.config.Configuration;
import com.huaweicloud.dis.agent.config.ConfigurationException;
import com.huaweicloud.dis.agent.metrics.Metrics;
import com.huaweicloud.dis.agent.tailing.FileFlow;
import com.huaweicloud.dis.agent.tailing.FileTailer;
import com.huaweicloud.dis.agent.tailing.checkpoints.FileCheckpointStore;
import com.huaweicloud.dis.agent.tailing.checkpoints.SQLiteFileCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Agent extends AbstractIdleService implements IHeartbeatProvider
{
    public static Logger logger = LoggerFactory.getLogger(Agent.class);
    
    private static volatile boolean dontShutdownOnExit = false;
    
    public static void main(String[] args)
        throws Exception
    {
        AgentOptions opts = AgentOptions.parse(args);
        String configFile = opts.getConfigFile();
        String agentName = opts.getAgentName();
        AgentConfiguration config = tryReadConfigurationFile(opts.getConfigFile());
        
        // Install an unhandled exception hook
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
        {
            @Override
            public void uncaughtException(Thread t, Throwable e)
            {
                if (e instanceof OutOfMemoryError || e instanceof LinkageError)
                {
                    // This prevents the JVM from hanging in case of an OOME and if we have a LinkageError
                    // we can't trust the JVM state.
                    dontShutdownOnExit = true;
                }
                String msg = "FATAL: Thread " + t.getName() + " threw an unrecoverable error. Aborting application";
                try
                {
                    try
                    { // We don't know if logging is still working
                        logger.error(msg, e);
                    }
                    finally
                    {
                        System.err.println(msg);
                        e.printStackTrace();
                    }
                }
                finally
                {
                    System.exit(1);
                }
            }
        });
        
        try
        {
            if (!agentName.matches("[a-zA-Z0-9-_]+"))
            {
                throw new IllegalArgumentException("agent name -n should be match [a-zA-Z0-9-_]+");
            }
            logger.info("Reading configuration from file: {}", configFile);
            if (config == null)
            {
                config = readConfigurationFile(opts.getConfigFile());
            }
            // Initialize and start the agent
            AgentContext agentContext = new AgentContext(agentName, config);
            if (agentContext.flows().isEmpty())
            {
                throw new ConfigurationException("There are no flows configured in configuration file.");
            }
            final Agent agent = new Agent(agentContext);
            
            // Make sure everything terminates cleanly when process is killed
            Runtime.getRuntime().addShutdownHook(new Thread()
            {
                @Override
                public void run()
                {
                    if (!dontShutdownOnExit && agent.isRunning())
                    {
                        agent.stopAsync();
                        agent.awaitTerminated();
                    }
                }
            });
            
            agent.startAsync();
            agent.awaitRunning();
            agent.awaitTerminated();
        }
        catch (Exception e)
        {
            logger.error("Unhandled error.", e);
            System.err.println();
            System.err.println("Agent has stopped because [" + e.getLocalizedMessage() + "]");
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static AgentConfiguration readConfigurationFile(String configFile)
        throws ConfigurationException
    {
        try
        {
            return new AgentConfiguration(Configuration.get(configFile));
        }
        catch (ConfigurationException ce)
        {
            throw ce;
        }
        catch (Exception e)
        {
            throw new ConfigurationException("Failed when reading configuration file: " + configFile, e);
        }
    }
    
    private static AgentConfiguration readConfigurationFile(Path configFile)
        throws ConfigurationException
    {
        try
        {
            return new AgentConfiguration(Configuration.get(configFile));
        }
        catch (ConfigurationException ce)
        {
            throw ce;
        }
        catch (Exception e)
        {
            throw new ConfigurationException("Failed when reading configuration file: " + configFile, e);
        }
    }
    
    private static AgentConfiguration tryReadConfigurationFile(Path configFile)
    {
        try
        {
            return readConfigurationFile(configFile);
        }
        catch (ConfigurationException ce)
        {
            return null;
        }
    }
    
    private static AgentConfiguration tryReadConfigurationFile(String configFile)
    {
        try
        {
            return readConfigurationFile(configFile);
        }
        catch (ConfigurationException ce)
        {
            return null;
        }
    }
    
    private final AgentContext agentContext;
    
    private final HeartbeatService heartbeat;
    
    private final List<ThreadPoolExecutor> sendingExecutorList;
    
    private final FileCheckpointStore checkpoints;
    
    private final Object lock = new Object();
    
    private final Stopwatch uptime = Stopwatch.createUnstarted();
    
    private String name;
    
    private AbstractScheduledService metricsEmitter;
    
    public Agent(AgentContext agentContext)
    {
        this.agentContext = agentContext;
        this.sendingExecutorList = new ArrayList<>();
        this.checkpoints = new SQLiteFileCheckpointStore(agentContext);
        this.heartbeat = new HeartbeatService(this.agentContext, 1, TimeUnit.SECONDS)
        {
            @Override
            protected Object heartbeat(AgentContext agent)
            {
                return Agent.this.heartbeat(agent);
            }
            
            @Override
            protected String serviceName()
            {
                return getClass().getSimpleName() + "[" + state().toString() + "]";
            }
        };
        this.metricsEmitter = new AbstractScheduledService()
        {
            @Override
            protected void runOneIteration()
                throws Exception
            {
                Agent.this.emitStatus();
            }
            
            @Override
            protected Scheduler scheduler()
            {
                return Scheduler.newFixedRateSchedule(Agent.this.agentContext.logStatusReportingPeriodSeconds(),
                    Agent.this.agentContext.logStatusReportingPeriodSeconds(),
                    TimeUnit.SECONDS);
            }
            
            @Override
            protected String serviceName()
            {
                return Agent.this.serviceName() + ".MetricsEmitter";
            }
            
            @Override
            protected void shutDown()
                throws Exception
            {
                logger.debug("{}: shutting down...", serviceName());
                // Emit status one last time before shutdown
                Agent.this.emitStatus();
                super.shutDown();
            }
        };
        this.name = getClass().getSimpleName();
    }
    
    @Override
    protected String serviceName()
    {
        return name;
    }
    
    private ThreadPoolExecutor getSendingExecutor(AgentContext agentContext)
    {
        // NOTE: This log line is parsed by scripts in support/benchmarking.
        // If it's modified the scripts need to be modified as well.
        logger.info("{}: Agent will use up to {} threads for sending data.",
            serviceName(),
            agentContext.maxSendingThreads());
        ThreadPoolExecutor tp = agentContext.createSendingExecutor();
        return tp;
    }
    
    private ThreadPoolExecutor getSendingExecutor(AgentContext agentContext, FileFlow fileFlow)
    {
        return agentContext.createFlowSendingExecutor(fileFlow);
    }
    
    @Override
    public void startUp()
    {
        logger.info("{}: Starting up...", serviceName());
        Stopwatch startupTimer = Stopwatch.createStarted();
        try
        {
            uptime.start();
            heartbeat.startAsync();
            synchronized (lock)
            {
                // TODO: Use Guava's ServiceManager
                // Start tailers
                for (FileFlow<?> flow : agentContext.flows())
                {
                    logger.info("{}: Starting tailer for file {}", serviceName(), flow.getId());
                    ThreadPoolExecutor sendingExecutor = getSendingExecutor(agentContext, flow);
                    sendingExecutorList.add(sendingExecutor);
                    FileTailer<?> tailer = flow.createTailer(checkpoints, sendingExecutor);
                    tailer.startAsync();
                    tailer.awaitRunning();
                }
            }
            metricsEmitter.startAsync();
        }
        catch (Exception e)
        {
            logger.error("Unhandled exception during startup.", e);
            System.err.println("Unhandled exception during startup.");
            e.printStackTrace();
            System.exit(1);
        }
        logger.info("{}({}): Startup completed in {} ms.", serviceName(), agentContext.getAgentName(), startupTimer.elapsed(TimeUnit.MILLISECONDS));
    }
    
    @Override
    protected void shutDown()
        throws Exception
    {
        logger.info("{}: Shutting down...", serviceName());
        Stopwatch shutdownTimer = Stopwatch.createStarted();
        try
        {
            synchronized (lock)
            {
                // Stopping all tailers
                for (FileFlow<?> flow : agentContext.flows())
                {
                    flow.getTailer().stopAsync();
                }
                metricsEmitter.stopAsync();
                heartbeat.stopAsync();
                for (ThreadPoolExecutor threadPoolExecutor : sendingExecutorList)
                {
                    threadPoolExecutor.shutdown();
                }
                // no more tasks are accepted, but current tasks will try to finish
                // Waiting for them to finish up... this should take less than agentContext.shutdownTimeMillis() (see
                // AsyncPublisher.getShutdownTimeMillis())
                for (FileFlow<?> flow : agentContext.flows())
                {
                    try
                    {
                        flow.getTailer().awaitTerminated();
                    }
                    catch (RuntimeException e)
                    {
                        logger.warn("{}: Error while waiting for tailer {} to stop during shutdown.",
                            serviceName(),
                            flow.getTailer(),
                            e);
                    }
                }
            }
            // Shutdown executor
            try
            {
                for (ThreadPoolExecutor threadPoolExecutor : sendingExecutorList)
                {
                    List<Runnable> tasks = threadPoolExecutor.shutdownNow();
                    logger.debug("{}: There were {} tasks that were not started due to shutdown.",
                        serviceName(),
                        tasks.size());
                    long remaining =
                        agentContext.shutdownTimeoutMillis() - shutdownTimer.elapsed(TimeUnit.MILLISECONDS);
                    if (remaining > 0)
                    {
                        logger.debug("{}: Waiting up to {} ms for any executing tasks to finish.",
                            serviceName(),
                            remaining);
                        try
                        {
                            if (!threadPoolExecutor.awaitTermination(remaining, TimeUnit.MILLISECONDS))
                            {
                                logger.info("{}: Not all executing send tasks finished cleanly by shutdown.",
                                    serviceName());
                            }
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            logger.debug("{}: Interrupted while waiting for executor to shutdown.", serviceName());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                logger.warn("{}: Error while waiting for executor service to stop during shutdown.", serviceName(), e);
            }
            // Shutdown heartbeats
            try
            {
                heartbeat.awaitTerminated();
            }
            catch (Exception e)
            {
                logger.warn("{}: Error while waiting for heartbeat service to stop during shutdown.", serviceName(), e);
            }
        }
        catch (Exception e)
        {
            String msg = String.format("%s: Unhandled exception during shutdown.", serviceName());
            try
            { // We don't know if logging is still working
                logger.error(msg, e);
            }
            finally
            {
                System.err.println(msg);
                e.printStackTrace();
            }
        }
        finally
        {
            uptime.stop();
            // Cleanly close the checkpoint store
            checkpoints.close();
            // Print final message
            String msg = String.format("%s: Shut down completed in %d ms. Uptime: %d ms",
                serviceName(),
                shutdownTimer.elapsed(TimeUnit.MILLISECONDS),
                uptime.elapsed(TimeUnit.MILLISECONDS));
            try
            { // We don't know if logging is still working
                logger.info(msg);
            }
            catch (Exception e)
            {
                System.err.println(msg);
                e.printStackTrace();
            }
        }
    }
    
    private void emitStatus()
    {
        try
        {
            Map<String, Map<String, Object>> metrics = getMetrics();
            if (agentContext.logEmitInternalMetrics())
            {
                if (metrics != null && !metrics.isEmpty())
                {
                    try
                    {
                        ObjectMapper mapper = new ObjectMapper();
                        logger.info("{}: Status: {}", serviceName(), mapper.writeValueAsString(metrics));
                    }
                    catch (JsonProcessingException e)
                    {
                        logger.error("{}: Failed when emitting status metrics.", serviceName(), e);
                    }
                }
            }
            // NOTE: This log line is parsed by scripts in support/benchmarking.
            // If it's modified the scripts need to be modified as well.
            logger.info(
                "{}: Progress: [{} records ({} bytes) / {} files ({} bytes)] parsed, and [{} records / {} files] sent successfully to destinations. Uptime: {}ms",
                serviceName(),
                metrics.get("Agent").get("TotalRecordsParsed"),
                metrics.get("Agent").get("TotalBytesConsumed"),
                metrics.get("Agent").get("TotalFilesParsed"),
                metrics.get("Agent").get("TotalFileBytesConsumed"),
                metrics.get("Agent").get("TotalRecordsSent"),
                metrics.get("Agent").get("TotalFilesSent"),
                uptime.elapsed(TimeUnit.MILLISECONDS));
            
            // Log a message if we're far behind in tailing the input
            long bytesBehind = (long)metrics.get("Agent").get("TotalBytesBehind");
            int filesBehind = (int)metrics.get("Agent").get("TotalFilesBehind");
            // NOTE: This log line is parsed by scripts in support/benchmarking.
            // If it's modified the scripts need to be modified as well.
            String msg = String.format("%s: Tailing is %02f MB (%d bytes) behind.",
                serviceName(),
                bytesBehind / 1024 / 1024.0,
                bytesBehind);
            if (filesBehind > 0)
            {
                msg += String.format(" There are %d file(s) newer than current file(s) being tailed.", filesBehind);
            }
            if (bytesBehind >= Metrics.BYTES_BEHIND_WARN_LEVEL)
            {
                logger.warn(msg);
            }
            else if (bytesBehind >= Metrics.BYTES_BEHIND_INFO_LEVEL || agentContext.logEmitInternalMetrics())
            {
                logger.info(msg);
            }
            else if (bytesBehind > 0)
            {
                logger.debug(msg);
            }
        }
        catch (Exception e)
        {
            logger.error("{}: Failed while emitting agent status.", serviceName(), e);
        }
    }
    
    public Map<String, Map<String, Object>> getMetrics()
    {
        Map<String, Map<String, Object>> metrics = new HashMap<>();
        synchronized (lock)
        {
            for (FileFlow<?> flow : agentContext.flows())
            {
                metrics.put(flow.getTailer().getId(), flow.getTailer().getMetrics());
            }
        }
        metrics.put("Agent", globalMetrics(metrics));
        return metrics;
    }
    
    private Map<String, Object> globalMetrics(Map<String, Map<String, Object>> metrics)
    {
        Map<String, Object> globalMetrics = new HashMap<>();
        long bytesBehind = 0;
        int filesBehind = 0;
        long bytesConsumed = 0;
        long recordsParsed = 0;
        long recordsSent = 0;
        
        long filesConsumed = 0;
        long filesParsed = 0;
        long filesSent = 0;
        
        AtomicLong zero = new AtomicLong(0);
        for (Map.Entry<String, Map<String, Object>> tailerMetrics : metrics.entrySet())
        {
            bytesBehind += Metrics.getMetric(tailerMetrics.getValue(), Metrics.FILE_TAILER_BYTES_BEHIND_METRIC, 0L);
            filesBehind += Metrics.getMetric(tailerMetrics.getValue(), Metrics.FILE_TAILER_FILES_BEHIND_METRIC, 0);
            bytesConsumed +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.PARSER_TOTAL_BYTES_CONSUMED_METRIC, zero).get();
            recordsParsed +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.PARSER_TOTAL_RECORDS_PARSED_METRIC, zero).get();
            recordsSent +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.SENDER_TOTAL_RECORDS_SENT_METRIC, zero).get();
            
            filesParsed +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.PARSER_TOTAL_FILES_PARSED_METRIC, zero).get();
            filesConsumed +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.PARSER_TOTAL_FILES_BYTES_CONSUMED_METRIC, zero)
                    .get();
            filesSent +=
                Metrics.getMetric(tailerMetrics.getValue(), Metrics.SENDER_TOTAL_FILES_SENT_METRIC, zero).get();
        }
        globalMetrics.put("TotalBytesBehind", bytesBehind);
        globalMetrics.put("TotalFilesBehind", filesBehind);
        globalMetrics.put("TotalBytesConsumed", bytesConsumed);
        globalMetrics.put("TotalRecordsParsed", recordsParsed);
        globalMetrics.put("TotalRecordsSent", recordsSent);
        
        globalMetrics.put("TotalFilesParsed", filesParsed);
        globalMetrics.put("TotalFileBytesConsumed", filesConsumed);
        globalMetrics.put("TotalFilesSent", filesSent);
        
        globalMetrics.put("MaxSendingThreads", agentContext.maxSendingThreads());
        globalMetrics.put("UpTimeMillis", uptime.elapsed(TimeUnit.MILLISECONDS));
        return globalMetrics;
    }
    
    /**
     * {@inheritDoc}
     * <p>
     * No need to create a separate thread for startup/shutdow. The agent uses the main thread.
     */
    @Override
    protected Executor executor()
    {
        return MoreExecutors.directExecutor();
    }
    
    @Override
    public Object heartbeat(AgentContext agent)
    {
        synchronized (lock)
        {
            for (FileFlow<?> flow : agentContext.flows())
            {
                flow.getTailer().heartbeat(agent);
            }
            return null;
        }
    }
}
