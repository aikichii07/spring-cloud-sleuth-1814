package com.example.demo;

import static org.apache.commons.lang3.exception.ExceptionUtils.wrapAndThrow;
import static org.joda.time.DateTime.now;

import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.runtime.Execution;
import org.joda.time.DateTime;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * FAKE service that checks MDC context and allows the BPMN process (defaultOrderProcess.bpmn20.xml) to run the happy path.
 * The public methods are called by the BPMN process.
 */
@Service
public class OrderWorkflowServiceImpl {
    private void sleep100() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            wrapAndThrow(e);
        }
    }

    private void assertTraceCtxInMdc() {
        assert StringUtils.isNoneBlank(MDC.get("traceId"));
        assert StringUtils.isNoneBlank(MDC.get("spanId"));
    }

    public void processWfLevelOne(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void continueProcessWfLevelOne(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void undoProcessWfLevelOne(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void processWfLevelTwo(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void undoProcessWfLevelTwo(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void continueProcessWfLevelTwo(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public void processWfOrderInternal(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        if (executionOrId instanceof ExecutionEntityImpl) {
            ((ExecutionEntityImpl) executionOrId).setVariable("provStatus", "OK");
        }
    }

    public boolean isOrderProcessSet(Execution execution) {
        sleep100();
        return false;
    }

    public void setOrderProcess(Execution execution) {
        //NOOP
        sleep100();
    }

    public DateTime getScheduledDate(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
        return now();
    }

    public boolean isScheduledInFuture(Object executionOrId) {
        sleep100();
        return false;
    }

    public void markWfCancel(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
    }

    public void markWfInProvisioning(Object executionOrId) {
        sleep100();
    }

    public void markWfActive(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
    }

    public void markWfInError(Object executionOrId) {
        assertTraceCtxInMdc();
        sleep100();
    }

    public void setProvisioningUser(Object executionOrId) {
        //NOOP
        sleep100();
    }
}
