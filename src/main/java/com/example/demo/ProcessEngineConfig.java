package com.example.demo;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.ACTIVITY_STARTED;
import static org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.ENTITY_INITIALIZED;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.core.io.support.ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX;

import com.zaxxer.hikari.HikariDataSource;
import org.activiti.compatibility.spring.SpringFlowable5CompatibilityHandlerFactory;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.cfg.multitenant.TenantInfoHolder;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.common.engine.impl.interceptor.CommandConfig;
import org.flowable.common.engine.impl.interceptor.CommandInterceptor;
import org.flowable.engine.FormService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.event.BaseEntityEventListener;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.idm.spring.SpringTransactionContextFactory;
import org.flowable.idm.spring.SpringTransactionInterceptor;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.job.service.impl.asyncexecutor.DefaultAsyncJobExecutor;
import org.flowable.job.service.impl.asyncexecutor.multitenant.ExecutorPerTenantAsyncExecutor;
import org.flowable.spring.ProcessEngineFactoryBean;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.sleuth.instrument.async.TraceableExecutorService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ContextResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;

@Configuration
public class ProcessEngineConfig {
    private static final Logger log = getLogger(ProcessEngineConfig.class);
    public static final String LOG_MDC_PROCESSDEFINITION_ID = "processDefinitionID";
    public static final String LOG_MDC_EXECUTION_ID = "executionId";
    public static final String LOG_MDC_PROCESSINSTANCE_ID = "processInstanceID";
    public static final String LOG_MDC_BUSINESS_KEY = "businessKey";
    public static final String LOG_MDC_ACTIVITY_ID = "activityId";
    public static final String ORDER_TENANT_ID = "orders";
    public static final String TICKET_TENANT_ID = "tickets";

    @Autowired
    private ResourcePatternResolver resourceLoader;

    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired @Lazy
    private OrderWorkflowServiceImpl orderWorkflowServiceImpl;

    /**
     * Determines the name to be used for the provided resource.
     *
     * @param resource the resource to get the name for
     *
     * @return the name of the resource
     */
    private static String determineResourceName(final Resource resource) {
        String resourceName = null;

        if (resource instanceof ContextResource) {
            resourceName = ((ContextResource) resource).getPathWithinContext();
        } else if (resource instanceof ByteArrayResource) {
            resourceName = resource.getDescription();
        } else {
            try {
                resourceName = resource.getFile().getAbsolutePath();
            } catch (IOException e) {
                resourceName = resource.getFilename();
            }
        }
        return resourceName;
    }

    private AsyncExecutor initAsyncExecutor(String tennantId, final int maxConcurrent) {
        final DefaultAsyncJobExecutor asyncExecutor = new DefaultAsyncJobExecutor();

        // special variables for future use
        // for now just to differentiate and make clear we are using two separate values
        // to calculate queue size below
        @SuppressWarnings("UnnecessaryLocalVariable") final int maxAsyncJobsDuePerAcquisition = maxConcurrent;
        @SuppressWarnings("UnnecessaryLocalVariable") final int maxTimerJobsPerAcquisition = maxConcurrent;

        // based on two types of acquisition counts
        final int queueSize = maxAsyncJobsDuePerAcquisition + maxTimerJobsPerAcquisition;

        // use something big - could be delays down the line (PI, network elements, etc.)
        final int asyncJobLockTimeInMillis = 86400000;

        asyncExecutor.setAsyncJobLockTimeInMillis(asyncJobLockTimeInMillis);

        asyncExecutor.setCorePoolSize(maxConcurrent / 2);
        asyncExecutor.setMaxPoolSize(maxConcurrent);

        asyncExecutor.setQueueSize(queueSize);

        asyncExecutor.setMaxAsyncJobsDuePerAcquisition(maxAsyncJobsDuePerAcquisition);
        asyncExecutor.setMaxTimerJobsPerAcquisition(maxTimerJobsPerAcquisition);

        asyncExecutor.setAutoActivate(false);

        // customized default async job executor initialization with tracing info
        // org.flowable.job.service.impl.asyncexecutor.DefaultAsyncJobExecutor.initAsyncJobExecutionThreadPool
        asyncExecutor.setThreadPoolQueue(new ArrayBlockingQueue<>(queueSize));
        asyncExecutor.setExecutorService(
            new TraceableExecutorService(
                this.applicationContext,
                new ThreadPoolExecutor(
                    asyncExecutor.getCorePoolSize(),
                    asyncExecutor.getMaxPoolSize(),
                    asyncExecutor.getKeepAliveTime(), TimeUnit.MILLISECONDS,
                    asyncExecutor.getThreadPoolQueue(),
                    new BasicThreadFactory.Builder()
                        .namingPattern(tennantId + "-flowable-async-job-executor-thread-%d")
                        .build()
                )
            )
        );

        return asyncExecutor;
    }

    @Bean
    public TenantInfoHolder tenantInfoHolder() {
        SimpleTenantInfoHolder tenantInfoHolder = new SimpleTenantInfoHolder();
        tenantInfoHolder.addTenant(ORDER_TENANT_ID);
        tenantInfoHolder.addTenant(TICKET_TENANT_ID);
        return tenantInfoHolder;
    }

    /**
     * Returns a list of embedded resource BPMN workflows.
     */
    private List<Resource> discoverProcessDefinitionResources(ResourcePatternResolver applicationContext,
                                                              String path) throws IOException {
        return ofNullable(applicationContext.getResources(path))
            .map(Arrays::asList)
            .orElse(emptyList());
    }

    /**
     * Delays staring the executor until the app has fully loaded.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startExecutionAfterTheAppIsReady() {
        final ProcessEngine processEngine;
        try {
            processEngine = processEngine().getObject();
        } catch (Exception e) {
            throw new RuntimeException("Process engine is not configured");
        }
        if (processEngine == null) {
            throw new RuntimeException("Process engine is not configured");
        }

        // setup logging MDC fillers
        final RuntimeService runtimeService = processEngine.getRuntimeService();
        runtimeService.addEventListener(
            new LoggingListener(),
            ENTITY_INITIALIZED
        );
        runtimeService.addEventListener(
            new ActivityLoggingListener(runtimeService),
            ACTIVITY_STARTED
        );

        final ExecutorPerTenantAsyncExecutor asyncExecutor = (ExecutorPerTenantAsyncExecutor) processEngine.getProcessEngineConfiguration().getAsyncExecutor();
        tenantInfoHolder().getAllTenants().forEach(tenantId -> asyncExecutor.addTenantAsyncExecutor(tenantId, false));
        asyncExecutor.start();
    }

    @EventListener(ContextClosedEvent.class)
    public void gracefullyStopExecutors() {
        final ProcessEngine processEngine;
        try {
            processEngine = processEngine().getObject();
        } catch (Exception e) {
            throw new RuntimeException("Process engine is not configured");
        }
        if (processEngine == null) {
            throw new RuntimeException("Process engine is not configured");
        }

        processEngine.getProcessEngineConfiguration()
            .getAsyncExecutor()
            .shutdown();
    }

    /**
     * Same config as the SpringProcessEngineConfiguration except it is using it's own connection pool and TX.
     * Makes it robust when "bad" java services don't catch exceptions properly by still writing the correct position in the WF.
     */
    @Bean
    public ProcessEngineConfigurationImpl processEngineConfiguration() {
        final ProcessEngineConfigurationImpl conf = new CrmProcessEngineConfiguration(
            transactionManager,
            primaryDataSource,
            flowable5CompatibilityFactory()
        );

        conf.initBeans();
        conf.getBeans().put("orderWorkflowService", orderWorkflowServiceImpl);

        // ensure we have enough spare db connections
        // otherwise the async executor will eat them up like candy
        final int maxConcurrent;
        if (primaryDataSource instanceof HikariDataSource) {
            maxConcurrent = ((HikariDataSource) primaryDataSource).getMaximumPoolSize();
        } else {
            maxConcurrent = 10;
        }

        // setup executor
        final ExecutorPerTenantAsyncExecutor asyncExecutor = new ExecutorPerTenantAsyncExecutor(
            tenantInfoHolder(),
            tenantId -> initAsyncExecutor(tenantId, maxConcurrent)
        );
        conf.setAsyncExecutor(asyncExecutor);
        conf.setAsyncExecutorActivate(false);

        return conf;
    }

    /**
     * @see <a href="https://www.flowable.org/docs/userguide/migration.html">Flowable Migration Guide : Flowable v5 to Flowable V6</a>
     */
    @Bean("flowable5CompatibilityFactory")
    public SpringFlowable5CompatibilityHandlerFactory flowable5CompatibilityFactory() {
        return new SpringFlowable5CompatibilityHandlerFactory();
    }

    /**
     * Creates a process engine and connects the bean resolver and expression language with the application context.
     * Taken from spring config verbatim.
     */
    @Bean
    public ProcessEngineFactoryBean processEngine() {
        ProcessEngineFactoryBean processEngineFactoryBean = new ProcessEngineFactoryBean();
        processEngineFactoryBean.setProcessEngineConfiguration(processEngineConfiguration());
        return processEngineFactoryBean;
    }

    /**
     * Needed to actually deploy the embedded workflows.
     */
    @Bean
    public InitializingBean orderDeploymentStrategy(RepositoryService repositoryService, TenantInfoHolder tenantInfoHolder) throws IOException {
        final String deploymentName = "OrderAutoDeployment";
        return () -> {
            tenantInfoHolder.getAllTenants().forEach(tenantId -> {
                final List<Resource> procDefResources;
                try {
                    procDefResources = discoverProcessDefinitionResources(
                        this.resourceLoader,
                        CLASSPATH_ALL_URL_PREFIX + "/processes/" + tenantId + "/*.bpmn20.xml"
                    );
                } catch (IOException e) {
                    throw new RuntimeException("Unable to read process definitions", e);
                }

                // Create a single deployment for all resources using the name hint as the literal name
                final DeploymentBuilder deploymentBuilder = repositoryService.createDeployment()
                    .enableDuplicateFiltering()
                    .name(deploymentName)
                    .category(tenantId)
                    .tenantId(tenantId);

                procDefResources.forEach(resource -> {
                    final String resourceName = determineResourceName(resource);

                    try {
                        if (resourceName.endsWith(".bar") || resourceName.endsWith(".zip") || resourceName.endsWith(".jar")) {
                            deploymentBuilder.addZipInputStream(new ZipInputStream(resource.getInputStream()));
                        } else {
                            deploymentBuilder.addInputStream(resourceName, resource.getInputStream());
                        }
                    } catch (IOException e) {
                        log.warn("couldn't auto deploy resource '{}': {}", resource, e.getMessage());
                    }
                });

                deploymentBuilder.deploy();
            });
        };
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public RuntimeService runtimeServiceBean(ProcessEngine processEngine) {
        return processEngine.getRuntimeService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public RepositoryService repositoryServiceBean(ProcessEngine processEngine) {
        return processEngine.getRepositoryService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public TaskService taskServiceBean(ProcessEngine processEngine) {
        return processEngine.getTaskService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public HistoryService historyServiceBean(ProcessEngine processEngine) {
        return processEngine.getHistoryService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public ManagementService managementServiceBeanBean(ProcessEngine processEngine) {
        return processEngine.getManagementService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public FormService formServiceBean(ProcessEngine processEngine) {
        return processEngine.getFormService();
    }

    /**
     * Taken from spring config.
     */
    @Bean
    public IdentityService identityServiceBean(ProcessEngine processEngine) {
        return processEngine.getIdentityService();
    }

    public static class SimpleTenantInfoHolder implements TenantInfoHolder {

        protected Map<String, List<String>> tenantToUserMapping = new HashMap<>();
        protected Map<String, String> userToTenantMapping = new HashMap<>();

        protected ThreadLocal<String> currentUserId = new ThreadLocal<>();
        protected ThreadLocal<String> currentTenantId = new ThreadLocal<>();

        @Override
        public Collection<String> getAllTenants() {
            return tenantToUserMapping.keySet();
        }

        public String getCurrentUserId() {
            return currentUserId.get();
        }

        public void setCurrentUserId(String userId) {
            currentUserId.set(userId);
            currentTenantId.set(userToTenantMapping.get(userId));
            Authentication.setAuthenticatedUserId(userId); // Activiti engine
        }

        public void clearCurrentUserId() {
            currentTenantId.set(null);
        }

        public void addTenant(String tenantId) {
            tenantToUserMapping.put(tenantId, new ArrayList<>());
            updateUserMap();
        }

        public void addUser(String tenantId, String userId) {
            tenantToUserMapping.get(tenantId).add(userId);
            updateUserMap();
        }

        void updateUserMap() {
            userToTenantMapping.clear();
            tenantToUserMapping.keySet().forEach(tenantId -> {
                List<String> userIds = tenantToUserMapping.get(tenantId);
                userIds.forEach(tenantUserId -> userToTenantMapping.put(tenantUserId, tenantId));
            });
        }

        @Override
        public void setCurrentTenantId(String tenantId) {
            currentTenantId.set(tenantId);
        }

        @Override
        public String getCurrentTenantId() {
            return currentTenantId.get();
        }

        @Override
        public void clearCurrentTenantId() {
            currentTenantId.set(null);
        }
    }

    public static class CrmProcessEngineConfiguration extends StandaloneProcessEngineConfiguration {
        private final PlatformTransactionManager transactionManager;

        public CrmProcessEngineConfiguration(PlatformTransactionManager transactionManager,
                                             DataSource dataSource,
                                             SpringFlowable5CompatibilityHandlerFactory springFlowable5CompatibilityHandlerFactory) {
            this.transactionManager = transactionManager;
            this.setDataSource(dataSource);

            // temporary for still pending processes (See ch6: https://www.flowable.org/docs/userguide/migration.html)
            this.setFlowable5CompatibilityEnabled(true);
            this.setFlowable5CompatibilityHandlerFactory(springFlowable5CompatibilityHandlerFactory);

            // spring specifics
            this.setDefaultCommandConfig(new CommandConfig().setContextReusePossible(true));

            // connect to the same database as the rest of spring
            this.setTransactionsExternallyManaged(true);
            this.setTransactionContextFactory(new SpringTransactionContextFactory(transactionManager, null));

            // update the ACT* tables
            this.setDatabaseSchemaUpdate(DB_SCHEMA_UPDATE_TRUE);

            // disable some not needed services
            this.setDisableIdmEngine(true);
            this.setJpaHandleTransaction(false);
            this.setJpaCloseEntityManager(false);

            // we want it ALL
            this.setHistoryLevel(HistoryLevel.FULL);

            // disable built in retry mechanism - failed executions go to the deadletter job queue immediately
            // where they can be revived via the management service moveDeadLetterJobToExecutableJob
            this.setAsyncExecutorNumberOfRetries(0);
        }

        @Override
        public CommandInterceptor createTransactionInterceptor() {
            return new SpringTransactionInterceptor(transactionManager);
        }
    }

    private static void setupMdc(ExecutionEntityImpl e) {
        if (e.getId() != null) {
            MDC.put(LOG_MDC_EXECUTION_ID, e.getId());
        }
        if (e.getProcessDefinitionId() != null) {
            MDC.put(LOG_MDC_PROCESSDEFINITION_ID, e.getProcessDefinitionId());
        }
        if (e.getProcessInstanceId() != null) {
            MDC.put(LOG_MDC_PROCESSINSTANCE_ID, e.getProcessInstanceId());
        }
        if (e.getProcessInstanceBusinessKey() != null) {
            MDC.put(LOG_MDC_BUSINESS_KEY, e.getProcessInstanceBusinessKey());
        }

        if (Objects.equals(e.getTenantId(), ORDER_TENANT_ID)) {
            MDC.put(
                "orderCode",
                defaultIfBlank(
                    e.getProcessInstanceBusinessKey(),
                    Objects.toString(e.getVariable("orderCode"))
                )
            );
        } else if (Objects.equals(e.getTenantId(), TICKET_TENANT_ID)) {
            MDC.put(
                "ticketCode",
                defaultIfBlank(
                    e.getProcessInstanceBusinessKey(),
                    Objects.toString(e.getVariable("ticketCode"))
                )
            );
        }
    }

    private static class LoggingListener extends BaseEntityEventListener {
        public LoggingListener() {
            super(false, ExecutionEntityImpl.class);
        }

        @Override
        protected void onCreate(FlowableEvent event) {
            final ExecutionEntityImpl entity = (ExecutionEntityImpl) ((FlowableEntityEvent) event).getEntity();
            setupMdc(entity);
        }

        @Override
        protected void onInitialized(FlowableEvent event) {
            if (!(event instanceof FlowableEntityEvent)) {
                return;
            }
            if (!(((FlowableEntityEvent) event).getEntity() instanceof ExecutionEntityImpl)) {
                return;
            }
            final ExecutionEntityImpl entity = (ExecutionEntityImpl) ((FlowableEntityEvent) event).getEntity();
            setupMdc(entity);
        }
    }

    private static class ActivityLoggingListener implements FlowableEventListener {
        final RuntimeService runtimeService;

        private ActivityLoggingListener(RuntimeService runtimeService) {
            this.runtimeService = runtimeService;
        }

        @Override
        public void onEvent(FlowableEvent event) {
            if (!(event instanceof FlowableActivityEvent)) {
                return;
            }

            final FlowableActivityEvent activityEvent = (FlowableActivityEvent) event;
            if (event.getType() == ACTIVITY_STARTED) {
                logOrderCode(activityEvent);
            }
        }

        private void logOrderCode(FlowableActivityEvent activityEvent) {
            final ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(activityEvent.getProcessInstanceId())
                .singleResult();

            if (!(processInstance instanceof ExecutionEntityImpl)) {
                return;
            }

            final ExecutionEntityImpl e = (ExecutionEntityImpl) processInstance;
            setupMdc(e);
        }

        @Override
        public boolean isFailOnException() {
            return false;
        }

        @Override
        public boolean isFireOnTransactionLifecycleEvent() {
            return false;
        }

        @Override
        public String getOnTransaction() {
            return null;
        }
    }
}
