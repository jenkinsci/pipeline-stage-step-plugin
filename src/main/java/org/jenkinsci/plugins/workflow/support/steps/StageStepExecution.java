package org.jenkinsci.plugins.workflow.support.steps;

import hudson.AbortException;
import java.util.Collections;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.steps.stage.Messages;

public class StageStepExecution extends AbstractStepExecutionImpl {
    private static final Logger LOGGER = Logger.getLogger(StageStepExecution.class.getName());

    // only used during the start() call, so no need to be persisted
    private transient final StageStep step;

    StageStepExecution(StepContext context, StageStep step) {
        super(context);
        this.step = step;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean start() throws Exception {
        if (getContext().hasBody()) { // recommended mode
            if (step.concurrency != null) {
                throw new AbortException(Messages.StageStepExecution_concurrency_not_supported_in_block_mode());
            }
            getContext().newBodyInvoker()
                    .withContexts(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class),
                            // NOTE: Other plugins should not be pulling from the environment to determine stage name.
                            // Use FlowNode.getEnclosingBlocks to get a list of enclosing blocks, innermost first, and
                            // look for the stage there.
                            EnvironmentExpander.constant(Collections.singletonMap("STAGE_NAME", step.name))))
                    .withCallback(BodyExecutionCallback.wrap(getContext()))
                    .withDisplayName(step.name)
                    .start();
            return false;
        }
        throw new AbortException(Messages.StageStepExecution_non_block_mode_deprecated());
    }

    private static final long serialVersionUID = 1L;

}
