package com.apigee.flow.execution.spi;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.message.MessageContext;

/**
 * Test stub for Apigee Execution interface.
 */
public interface Execution {
    ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext);
}
