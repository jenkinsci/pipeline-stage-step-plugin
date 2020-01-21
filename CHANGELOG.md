## Changelog

### 2.3 

Release date: 2017-11-02

-   [JENKINS-44456](https://issues.jenkins-ci.org/browse/JENKINS-44456) -
    Expose stage name as an environment variable.

### 2.2 

Release date: 2016-08-30

-   [JENKINS-26107](https://issues.jenkins-ci.org/browse/JENKINS-26107)
    `stage` now takes a block argument; the old syntax, interspersed
    with other statements, is deprecated.
    -   You must also update the [Pipeline Stage View
        Plugin](https://plugins.jenkins.io/pipeline-stage-view)
        to 2.0+ to see block-syntax stages at all. Nested stages are
        currently displayed linearly.
    -   The `concurrency` parameter is also deprecated; use `lock` from
        the [Lockable Resources
        Plugin](https://plugins.jenkins.io/lockable-resources),
        and/or `milestone` from the [Pipeline Milestone Step
        Plugin](https://plugins.jenkins.io/pipeline-milestone-step).

### 2.1 

Release date: 2016-05-03

-   Now preventing `stage` from being used inside a `parallel` branch,
    which was never supported.
-   Downgrading an assertion error to a warning.

### 2.0 

Release date: 2016-04-05

-   First release under per-plugin versioning scheme. See [1.x
    changelog](https://github.com/jenkinsci/workflow-plugin/blob/82e7defa37c05c5f004f1ba01c93df61ea7868a5/CHANGES.md)
    for earlier releases.
-   Includes the `stage` step formerly in [Pipeline Supporting APIs
    Plugin](https://plugins.jenkins.io/workflow-support).