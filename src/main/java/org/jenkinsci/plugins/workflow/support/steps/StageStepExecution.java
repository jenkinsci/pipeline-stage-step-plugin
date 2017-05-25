package org.jenkinsci.plugins.workflow.support.steps;

import com.google.inject.Inject;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.InvisibleAction;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.XStream2;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import static java.util.logging.Level.WARNING;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.steps.stage.Messages;

public class StageStepExecution extends AbstractStepExecutionImpl {
    private static final Logger LOGGER = Logger.getLogger(StageStepExecution.class.getName());

    // only used during the start() call, so no need to be persisted
    @Inject(optional=true) private transient StageStep step;
    @StepContextParameter private transient Run<?,?> run;
    @StepContextParameter private transient FlowNode node;

    private static final class StageActionImpl extends InvisibleAction implements StageAction {
        private final String stageName;
        StageActionImpl(String stageName) {
            this.stageName = stageName;
        }
        @Override public String getStageName() {
            return stageName;
        }
    }

    @Override
    public boolean start() throws Exception {
        if (getContext().hasBody()) { // recommended mode
            if (step.concurrency != null) {
                throw new AbortException(Messages.StageStepExecution_concurrency_not_supported_in_block_mode());
            }
            getContext().newBodyInvoker()
                    .withContexts(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class),
                            new ExpanderImpl(step.name)))
                    .withCallback(BodyExecutionCallback.wrap(getContext()))
                    .withDisplayName(step.name)
                    .start();
            return false;
        }
        getContext().get(TaskListener.class).getLogger().println(Messages.StageStepExecution_non_block_mode_deprecated());
        if (isInsideParallel(node)) {
            throw new AbortException(Messages.StageStepExecution_the_stage_step_must_not_be_used_inside_a());
        }
        node.addAction(new LabelAction(step.name));
        node.addAction(new StageActionImpl(step.name));
        enter(run, getContext(), step.name, step.concurrency);
        return false; // execute asynchronously
    }

    // TODO switch to FlowNodeSerialWalker or equivalent from https://github.com/jenkinsci/workflow-api-plugin/pull/2
    private static boolean isInsideParallel(FlowNode n) {
        while (true) {
            if (n instanceof BlockEndNode) {
                n = ((BlockEndNode) n).getStartNode();
            }
            if (n.getAction(ThreadNameAction.class) != null) {
                return true;
            }
            List<FlowNode> parents = n.getParents();
            if (parents.isEmpty()) {
                assert n instanceof FlowStartNode;
                return false;
            }
            assert parents.size() == 1;
            n = parents.get(0);
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // TODO
        throw new UnsupportedOperationException();
    }

    private static XmlFile getConfigFile() throws IOException {
        Jenkins j = Jenkins.getInstance();
        if (j == null) {
            throw new IOException("Jenkins is not running"); // do not use Jenkins.getActiveInstance() as that is an ISE
        }
        return new XmlFile(DEFAULT_XSTREAM, new File(j.getRootDir(), StageStep.class.getName() + ".xml"));
    }
    /** JENKINS-19561 workaround: keep our own instance, unshared with other {@link XmlFile}s, and guarded by the static monitor in {@link #load} and {@link #save}. */
    private static final XStream2 DEFAULT_XSTREAM = new XStream2();

    // TODO can this be replaced with StepExecutionIterator?
    private static Map<String,Map<String,Stage>> stagesByNameByJob;

    // TODO or delete and make this an instance field in DescriptorImpl
    public static void clear() {
        stagesByNameByJob = null;
    }

    @SuppressWarnings("unchecked")
    private static synchronized void load() {
        if (stagesByNameByJob == null) {
            stagesByNameByJob = new TreeMap<String,Map<String,Stage>>();
            try {
                XmlFile configFile = getConfigFile();
                if (configFile.exists()) {
                    stagesByNameByJob = (Map<String,Map<String,Stage>>) configFile.read();
                }
            } catch (IOException x) {
                LOGGER.log(WARNING, null, x);
            }
            LOGGER.log(Level.FINE, "load: {0}", stagesByNameByJob);
        }
    }

    private static synchronized void save() {
        try {
            getConfigFile().write(stagesByNameByJob);
        } catch (IOException x) {
            LOGGER.log(WARNING, null, x);
        }
        LOGGER.log(Level.FINE, "save: {0}", stagesByNameByJob);
    }

    private static synchronized void enter(Run<?,?> r, StepContext context, String name, Integer concurrency) {
        LOGGER.log(Level.FINE, "enter {0} {1}", new Object[] {r, name});
        println(context, "Entering stage " + name);
        load();
        Job<?,?> job = r.getParent();
        String jobName = job.getFullName();
        Map<String,Stage> stagesByName = stagesByNameByJob.get(jobName);
        if (stagesByName == null) {
            stagesByName = new TreeMap<String,Stage>();
            stagesByNameByJob.put(jobName, stagesByName);
        }
        Stage stage = stagesByName.get(name);
        if (stage == null) {
            stage = new Stage();
            stagesByName.put(name, stage);
        }
        stage.concurrency = concurrency;
        int build = r.number;
        if (stage.waitingContext != null) {
            // Someone has got to give up.
            if (stage.waitingBuild < build) {
                // Cancel the older one.
                try {
                    cancel(stage.waitingContext, context);
                } catch (Exception x) {
                    LOGGER.log(WARNING, "could not cancel an older flow (perhaps since deleted?)", x);
                }
            } else if (stage.waitingBuild > build) {
                // Cancel this one. And work with the older one below, instead of the one initiating this call.
                try {
                    cancel(context, stage.waitingContext);
                } catch (Exception x) {
                    LOGGER.log(WARNING, "could not cancel the current flow", x);
                }
                build = stage.waitingBuild;
                context = stage.waitingContext;
            } else {
                throw new IllegalStateException("the same flow is trying to reënter the stage " + name); // see 'e' with two dots, that's Jesse Glick for you! - KK
            }
        }
        for (Map.Entry<String,Stage> entry : stagesByName.entrySet()) {
            if (entry.getKey().equals(name)) {
                continue;
            }
            Stage stage2 = entry.getValue();
            // If we were holding another stage in the same job, release it, unlocking its waiter to proceed.
            if (stage2.holding.remove(build)) {
                if (stage2.waitingContext != null) {
                    stage2.unblock("Unblocked since " + r.getDisplayName() + " is moving into stage " + name);
                }
            }
        }
        stage.waitingBuild = build;
        stage.waitingContext = context;
        if (stage.concurrency == null || stage.holding.size() < stage.concurrency) {
            stage.unblock("Proceeding");
        } else {
            println(context, "Waiting for builds " + stage.holding);
        }
        cleanUp(job, jobName);
        save();
    }

    private static synchronized void exit(Run<?,?> r) {
        load();
        LOGGER.log(Level.FINE, "exit {0}: {1}", new Object[] {r, stagesByNameByJob});
        Job<?,?> job = r.getParent();
        String jobName = job.getFullName();
        Map<String,Stage> stagesByName = stagesByNameByJob.get(jobName);
        if (stagesByName == null) {
            return;
        }
        boolean modified = false;
        for (Stage stage : stagesByName.values()) {
            if (stage.holding.contains(r.number)) {
                stage.holding.remove(r.number); // XSTR-757: do not rely on return value of TreeSet.remove(Object)
                modified = true;
                if (stage.waitingContext != null) {
                    stage.unblock("Unblocked since " + r.getDisplayName() + " finished");
                }
            }
        }
        if (modified) {
            cleanUp(job, jobName);
            save();
        }
    }

    private static void cleanUp(Job<?,?> job, String jobName) {
        Map<String,Stage> stagesByName = stagesByNameByJob.get(jobName);
        assert stagesByName != null;
        Iterator<Entry<String,Stage>> it = stagesByName.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String,Stage> entry = it.next();
            Set<Integer> holding = entry.getValue().holding;
            Iterator<Integer> it2 = holding.iterator();
            while (it2.hasNext()) {
                Integer number = it2.next();
                if (job.getBuildByNumber(number) == null) {
                    // Deleted at some point but did not properly clean up from exit(…).
                    LOGGER.log(WARNING, "Cleaning up apparently deleted {0}#{1}", new Object[] {jobName, number});
                    it2.remove();
                }
            }
            if (holding.isEmpty()) {
                assert entry.getValue().waitingContext == null : entry;
                it.remove();
            }
        }
        if (stagesByName.isEmpty()) {
            stagesByNameByJob.remove(jobName);
        }
    }

    private static void println(StepContext context, String message) {
        if (!context.isReady()) {
            LOGGER.log(Level.FINE, "cannot print message ‘{0}’ to dead {1}", new Object[] {message, context});
            return;
        }
        try {
            context.get(TaskListener.class).getLogger().println(message);
        } catch (Exception x) {
            LOGGER.log(WARNING, "failed to print message to dead " + context, x);
        }
    }

    // TODO record the stage it got to and display that
    private static void cancel(StepContext context, StepContext newer) throws IOException, InterruptedException {
        if (context.isReady() && newer.isReady()) {
            println(context, "Canceled since " + newer.get(Run.class).getDisplayName() + " got here");
            println(newer, "Canceling older " + context.get(Run.class).getDisplayName());
            context.onFailure(new FlowInterruptedException(Result.NOT_BUILT, new CanceledCause(newer.get(Run.class))));
        } else {
            LOGGER.log(WARNING, "cannot cancel dead {0} or {1}", new Object[] {context, newer});
        }
    }

    /**
     * Records that a flow was canceled while waiting in a stage step because a newer flow entered that stage instead.
     */
    public static final class CanceledCause extends CauseOfInterruption {

        private static final long serialVersionUID = 1;

        private final String newerBuild;

        CanceledCause(Run<?,?> newerBuild) {
            this.newerBuild = newerBuild.getExternalizableId();
        }

        public Run<?,?> getNewerBuild() {
            return Run.fromExternalizableId(newerBuild);
        }

        @Override public String getShortDescription() {
            return "Superseded by " + getNewerBuild().getDisplayName();
        }

    }

    private static final class Stage {
        /** Numbers of builds currently running in this stage. */
        final Set<Integer> holding = new TreeSet<Integer>();
        /** Maximum permitted size of {@link #holding}, or null for unbounded. */
        @CheckForNull
        Integer concurrency;
        /** Context of the build currently waiting to enter this stage, if any. */
        @CheckForNull StepContext waitingContext;
        /** Number of the build corresponding to {@link #waitingContext}, if any. */
        @Nullable
        Integer waitingBuild;
        @Override public String toString() {
            return "Stage[holding=" + holding + ",waitingBuild=" + waitingBuild + ",concurrency=" + concurrency + "]";
        }
        /**
         * Unblocks the build currently waiting.
         * @param message a message to print to the log of the unblocked build
         */
        void unblock(String message) {
            assert Thread.holdsLock(StageStepExecution.class);
            assert waitingContext != null : message;
            assert waitingBuild != null : message;
            if (holding.contains(waitingBuild)) {
                LOGGER.log(WARNING, "{0}: {1} already in {2}", new Object[] {message, waitingBuild, holding});
            }
            /* Not necessarily true, since a later build could reduce the concurrency of an existing stage; could perhaps adjust semantics to skip unblocking in this special case:
            assert concurrency == null || holding.size() < concurrency;
            */
            println(waitingContext, message);
            waitingContext.onSuccess(null);
            holding.add(waitingBuild);
            waitingContext = null;
            waitingBuild = null;
        }
    }

    private static final class ExpanderImpl extends EnvironmentExpander {
        private static final long serialVersionUID = 1;
        private final String stageName;
        private ExpanderImpl(String stageName) {
            this.stageName = stageName;
        }
        @Override public void expand(EnvVars env) throws IOException, InterruptedException {
            env.override("STAGE_NAME", stageName);
        }
    }


    @Extension
    public static final class Listener extends RunListener<Run<?,?>> {
        @Override public void onCompleted(Run<?,?> r, TaskListener listener) {
            if (!(r instanceof FlowExecutionOwner.Executable) || ((FlowExecutionOwner.Executable) r).asFlowExecutionOwner() == null) {
                return;
            }
            exit(r);
        }
    }

    private static final long serialVersionUID = 1L;

}
