package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
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

    /** @deprecated only to be able to load old builds, and for a deprecated run mode */
    @Deprecated
    private static final class StageActionImpl extends InvisibleAction implements org.jenkinsci.plugins.workflow.actions.StageAction {
        private final String stageName;
        StageActionImpl(String stageName) {
            this.stageName = stageName;
        }
        @Override public String getStageName() {
            return stageName;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean start() throws Exception {
        if (step.concurrency != null) {
            throw new AbortException(Messages.StageStepExecution_concurrency_not_supported());
        }
        if (getContext().hasBody()) { // recommended mode
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
        getContext().get(TaskListener.class).getLogger().println(Messages.StageStepExecution_non_block_mode_deprecated());
        FlowNode node = getContext().get(FlowNode.class);
        node.addAction(new LabelAction(step.name));
        node.addAction(new StageActionImpl(step.name));
        getContext().onSuccess(null);
        return true;
    }

    /** @deprecated only to be able to load old builds */
    @Deprecated
    public static final class CanceledCause extends CauseOfInterruption {

        private static final long serialVersionUID = 1;

        @SuppressFBWarnings(value = "UWF_UNWRITTEN_FIELD", justification = "correct")
        private final String newerBuild;

        private CanceledCause() {
            throw new AssertionError("no longer constructed");
        }

        public Run<?,?> getNewerBuild() {
            return Run.fromExternalizableId(newerBuild);
        }

        @Override public String getShortDescription() {
            return "Superseded by " + getNewerBuild().getDisplayName();
        }

    }

    private static final long serialVersionUID = 1L;

}
