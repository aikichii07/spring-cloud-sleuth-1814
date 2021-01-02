package com.example.demo;

import static com.example.demo.ProcessEngineConfig.ORDER_TENANT_ID;
import static com.example.demo.WorkflowId.workflowIdent;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.flowable.engine.impl.test.JobTestHelper.areJobsOrExecutableTimersAvailable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.flowable.common.engine.impl.cfg.multitenant.TenantInfoHolder;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.job.service.impl.asyncexecutor.multitenant.ExecutorPerTenantAsyncExecutor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ContextResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

@SpringBootTest
public class OrderProcessIT {
    @TestConfiguration
    static class Config {

        @Bean(name = "isolatedTransactionTemplate")
        @ConditionalOnMissingBean(name = "isolatedTransactionTemplate")
        public FunctionalTransactionTemplate isolatedTransactionTemplate(PlatformTransactionManager transactionManager) {
            final FunctionalTransactionTemplate transactionTemplate = new FunctionalTransactionTemplate(transactionManager);
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transactionTemplate;
        }

        @Bean
        public LockRegistry lockRegistry() {
            return new DefaultLockRegistry();
        }
    }

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private FunctionalTransactionTemplate transactionTemplate;

    @Autowired
    private TenantInfoHolder tenantInfoHolder;

    @SpyBean
    private OrderWorkflowServiceImpl orderWorkflowService;

    /**
     * Returns a list of embedded resource BPMN workflows.
     */
    private static List<Resource> discoverProcessDefinitionResources(ResourcePatternResolver applicationContext,
                                                                     String path) throws IOException {
        return ofNullable(applicationContext.getResources(path))
            .map(Arrays::asList)
            .orElse(emptyList());
    }

    /**
     * Determines the name to be used for the provided resource.
     *
     * @param resource the resource to get the name for
     *
     * @return the name of the resource
     */
    private static String determineResourceName(final Resource resource) {
        String resourceName;
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

    private static String normalizeLineEnds(String s) {
        return s.replace("\r\n", "\n")
            .replace('\r', '\n');
    }

    @BeforeEach
    public void setUpAll() throws Exception {
        reset(orderWorkflowService);

        tenantInfoHolder.setCurrentTenantId(ORDER_TENANT_ID);

        final List<Resource> procDefResources = discoverProcessDefinitionResources(
            new GenericApplicationContext(),
            "classpath*:/processes/orders/*.bpmn20.xml"
        );

        final DeploymentBuilder deploymentBuilder = repositoryService.createDeployment()
            .tenantId(ORDER_TENANT_ID);
        for (Resource resource : procDefResources) {
            final String resourceName = determineResourceName(resource);
            if (resourceName.endsWith(".bar") || resourceName.endsWith(".zip") || resourceName.endsWith(".jar")) {
                deploymentBuilder.addZipInputStream(new ZipInputStream(resource.getInputStream()));
            } else {
                deploymentBuilder.addInputStream(resourceName, resource.getInputStream());
            }
        }
        deploymentBuilder.deploy();
    }

    @AfterEach
    public void tearDown() {
        transactionTemplate.execute(() -> {
            processEngine.getRuntimeService().createProcessInstanceQuery()
                .list()
                .forEach(pi -> processEngine.getRuntimeService().deleteProcessInstance(pi.getProcessInstanceId(), "tearDown"));
        });
    }

    /**
     * Starts the default order process.
     */
    @NotNull WorkflowId startProcess(String processName) {
        return startProcess(processName, new HashMap<>());
    }

    /**
     * Starts the default order process with variables.
     */
    @NotNull WorkflowId startProcess(String processName, Map<String, Object> variables) {
        final String orderCode = UUID.randomUUID().toString();
        variables.putIfAbsent("orderCode", orderCode);

        final ProcessInstance processInstance = transactionTemplate.execute(() -> {
            return processEngine.getRuntimeService().startProcessInstanceByKeyAndTenantId(processName, orderCode, variables, ORDER_TENANT_ID);
        });

        waitForAllJobsProcessed();

        return workflowIdent(processInstance.getId());
    }

    void waitForAllJobsProcessed() {
        final ManagementService managementService = processEngine.getManagementService();
        final AsyncExecutor asyncExecutor = processEngine.getProcessEngineConfiguration().getAsyncExecutor();

        long t1 = currentTimeMillis();
        while (areJobsOrExecutableTimersAvailable(managementService)) {
            if (asyncExecutor instanceof ExecutorPerTenantAsyncExecutor) {
                ((ExecutorPerTenantAsyncExecutor) asyncExecutor).getTenantAsyncExecutor(ORDER_TENANT_ID).start();
            }
            if (currentTimeMillis() - t1 > 10000L) {
                break;
            } else {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Asserts that order process is finished .
     * First assert process instance is ended
     * Second assert process instance which should be null if process is finished (not in runtime anymore)
     */
    void assertOrderProcessEnded(WorkflowId processInstance) {
        //check process instance ended
        //assertNotNull(processInstance);
        //assertTrue(processInstance.isEnded());

        //check runtime that the process is finished
        waitForAllJobsProcessed();
        final ProcessInstance newProcessInstance = transactionTemplate.execute(() -> processEngine.getRuntimeService().createProcessInstanceQuery()
            .processInstanceId(processInstance.toString())
            .singleResult());
        if (newProcessInstance != null) {
            assertThat(newProcessInstance.isEnded())
                .isTrue();
        }
    }

    /**
     * Asserts that Order is activated successfully.
     * First assert Mockito object - if "Mark active" service task was executed
     */
    void assertOrderSuccessfullyActivated(WorkflowId processInstance) {
        verify(orderWorkflowService, times(1)).markWfActive(any(Execution.class));
    }

    /**
     * Asserts that Order is activated successfully.
     * First assert Mockito object - if "Mark active" service task was executed
     */
    void assertOrderProvisioned(WorkflowId processInstance) {
        verify(orderWorkflowService, times(1)).processWfLevelOne(any(Execution.class));
        verify(orderWorkflowService, times(1)).processWfLevelTwo(any(Execution.class));
        verify(orderWorkflowService, times(1)).processWfOrderInternal(any(Execution.class));
    }

    @Nested
    public class DefaultOrderProcessTest {

        @Test
        public void testHappyPath_provisioningOk_orderSuccessfullyActivated() {
            // test (assumption all provisioning is OK)
            WorkflowId processInstance = startProcess("defaultOrderProcess");
            waitForAllJobsProcessed();

            // verify
            assertOrderProcessEnded(processInstance);
            assertOrderSuccessfullyActivated(processInstance);
            assertOrderProvisioned(processInstance);
        }
    }
}
