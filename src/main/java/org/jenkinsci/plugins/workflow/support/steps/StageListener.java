package org.jenkinsci.plugins.workflow.support.steps;

import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Receives notifications about stages.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class StageListener implements ExtensionPoint {


    /**
     * Called as a pipeline enters a stage.
     *
     * @param run
     *      The current Pipeline execution.
     * @param name
     *      The stage name pipeline is entering into.
     * @throws RuntimeException
     *      Any exception/error thrown from this method will be swallowed to prevent broken listeners
     *      from breaking all the builds.
     */
    public abstract void onEnterred(Run run, String name);
}
