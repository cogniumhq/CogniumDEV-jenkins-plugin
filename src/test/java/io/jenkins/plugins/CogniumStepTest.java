package io.jenkins.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.FormValidation;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CogniumStepTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void descriptorRegistersCogniumFunction() {
        StepDescriptor descriptor = j.jenkins.getDescriptorByType(CogniumStep.DescriptorImpl.class);
        assertEquals("cognium", descriptor.getFunctionName());
        assertEquals("Cognium SAST", descriptor.getDisplayName());
        assertFalse(descriptor.takesImplicitBlockArgument());
        assertTrue(descriptor.getRequiredContext().contains(hudson.FilePath.class));
    }

    @Test
    void defaultsMatchDocumentedValues() {
        CogniumStep step = new CogniumStep();
        assertEquals(".", step.getPath());
        assertEquals("latest", step.getPackageVersion());
        assertEquals("text", step.getFormat());
        assertNull(step.getOutput());
        assertNull(step.getSeverity());
        assertNull(step.getCategory());
        assertFalse(step.isFailOnFindings());
    }

    @Test
    void blankSettersRestoreDefaults() {
        CogniumStep step = new CogniumStep();
        step.setPath("  ");
        step.setPackageVersion("");
        step.setFormat(" ");
        assertEquals(".", step.getPath());
        assertEquals("latest", step.getPackageVersion());
        assertEquals("text", step.getFormat());
    }

    @Test
    void descriptorRejectsUnsafePackageVersion() {
        CogniumStep.DescriptorImpl descriptor = j.jenkins.getDescriptorByType(CogniumStep.DescriptorImpl.class);
        FormValidation validation = descriptor.doCheckPackageVersion("1.0.0; rm -rf /");
        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPackageVersion("latest").kind);
    }

    @Test
    void pipelineRejectsTraversalOutOfTheWorkspace() throws Exception {
        WorkflowRun run = runPipeline(true, false, null, "cognium(path: '../other-job')");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("'..' traversal is not allowed", run);
        j.assertLogNotContains("[fake-npm]", run);
    }

    @Test
    void pipelineRejectsAbsoluteReportOutput() throws Exception {
        WorkflowRun run = runPipeline(true, false, null, "cognium(output: '/tmp/cognium-report.sarif')");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("absolute paths are not allowed", run);
        j.assertLogNotContains("[fake-npm]", run);
    }

    @Test
    void pipelineRejectsSymlinkThatEscapesTheWorkspace() throws Exception {
        assumeFalse(FakeCogniumEnvironment.isWindowsAgent(), "uses ln -s");
        WorkflowRun run = runPipeline(
                true,
                false,
                null,
                "sh 'mkdir -p ../cognium-outside && ln -s ../cognium-outside escape'\n"
                        + "    cognium(path: 'escape')");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("symbolic link that leaves the Jenkins workspace", run);
        j.assertLogNotContains("[fake-npm]", run);
    }

    @Test
    void pipelineRejectsReportThroughSymlinkThatEscapesTheWorkspace() throws Exception {
        assumeFalse(FakeCogniumEnvironment.isWindowsAgent(), "uses ln -s");
        WorkflowRun run = runPipeline(
                true,
                false,
                null,
                "sh 'mkdir -p ../cognium-outside && ln -s ../cognium-outside reports'\n"
                        + "    cognium(output: 'reports/out.sarif')");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("symbolic link that leaves the Jenkins workspace", run);
        j.assertLogNotContains("[fake-npm]", run);
    }

    @Test
    void pipelineCanInvokeCognium() throws Exception {
        WorkflowRun run = runPipeline(true, false, null, "cognium(path: \".\")");
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("[Cognium] Scan completed successfully", run);
        j.assertLogContains("[fake-cognium-dev]", run);
    }

    @Test
    void installationFailureFailsTheBuild() throws Exception {
        WorkflowRun run = runPipeline(true, false, "'COGNIUM_TEST_NPM_FAIL=1'", "cognium(path: \".\")");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("Failed to install cognium-dev", run);
        assertNoInstallLeftovers();
    }

    @Test
    void missingNpmFailsTheBuild() throws Exception {
        WorkflowRun run = runPipeline(false, true, null, "cognium()");
        j.assertBuildStatus(Result.FAILURE, run);
        String log = run.getLog();
        assertTrue(
                log.contains("Failed to run npm")
                        || log.contains("Failed to install cognium-dev")
                        || log.contains("not recognized"),
                "missing npm should fail the step: " + log);
    }

    @Test
    void successfulScanCleansTemporaryInstall() throws Exception {
        WorkflowRun run = runPipeline(true, false, null, "cognium()");
        j.assertBuildStatusSuccess(run);
        assertNoInstallLeftovers();
    }

    @Test
    void findingsDoNotFailThePipelineByDefault() throws Exception {
        WorkflowRun run =
                runPipeline(true, false, "'COGNIUM_TEST_EXIT_CODE=1'", "cognium(path: \".\", failOnFindings: false)");
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("failOnFindings=false", run);
        assertNoInstallLeftovers();
    }

    @Test
    void findingsFailThePipelineWhenRequested() throws Exception {
        WorkflowRun run =
                runPipeline(true, false, "'COGNIUM_TEST_EXIT_CODE=1'", "cognium(path: \".\", failOnFindings: true)");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("Cognium detected security findings", run);
        assertNoInstallLeftovers();
    }

    @Test
    void oldNodeVersionFailsTheBuild() throws Exception {
        WorkflowRun run = runPipelineWithNodeVersion("v20.18.0", "cognium()");

        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("Node.js v20.18.0 is not supported", run);
        assertNoInstallLeftovers();
    }

    @Test
    void minimumNodeVersionSucceeds() throws Exception {
        WorkflowRun run = runPipelineWithNodeVersion("v20.19.0", "cognium()");

        j.assertBuildStatusSuccess(run);
        j.assertLogContains("Node.js v20.19.0 detected", run);
        assertNoInstallLeftovers();
    }

    @Test
    void operationalErrorFailsRegardlessOfFailOnFindings() throws Exception {
        WorkflowRun run = runPipeline(true, false, "'COGNIUM_TEST_EXIT_CODE=2'", "cognium(failOnFindings: false)");
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("exit code 2", run);
        assertNoInstallLeftovers();
    }

    @Test
    void commandSelectionMatchesAgentOs() throws Exception {
        WorkflowRun run = runPipeline(true, false, null, "cognium()");
        j.assertBuildStatusSuccess(run);
        assertTrue(
                run.getLog().contains("[fake-npm] installed into"),
                "expected fake npm to run on " + FakeCogniumEnvironment.platformLabel());
        assertInstanceOf(CogniumStep.DescriptorImpl.class, j.jenkins.getDescriptor(CogniumStep.class));
        assertEquals(
                CogniumCli.npmExecutable(!FakeCogniumEnvironment.isWindowsAgent()),
                FakeCogniumEnvironment.expectedNpmName());
        assertEquals(
                List.of("npm.cmd", "install"),
                CogniumCli.processArguments(false, List.of("npm.cmd", "install"))
                        .toList());
        assertEquals(
                "cmd.exe",
                CogniumCli.processArguments(false, List.of("node", "-v"))
                        .toList()
                        .get(0));
        assertTrue(CogniumCli.processArguments(true, List.of("npm", "install"))
                .toList()
                .contains("npm"));
    }

    private WorkflowRun runPipeline(boolean includeNpm, boolean isolatePath, String extraEnv, String stepCall)
            throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "cognium-" + System.nanoTime());
        job.setDefinition(new CpsFlowDefinition(
                FakeCogniumEnvironment.script(includeNpm, isolatePath, extraEnv, stepCall), true));
        QueueTaskFuture<WorkflowRun> future = job.scheduleBuild2(0);
        WorkflowRun run = future.get();
        return j.waitForCompletion(run);
    }

    private WorkflowRun runPipelineWithNodeVersion(String nodeVersion, String stepCall) throws Exception {

        WorkflowJob job = j.createProject(WorkflowJob.class, "cognium-node-version-" + System.nanoTime());

        job.setDefinition(new CpsFlowDefinition(
                FakeCogniumEnvironment.scriptWithNodeVersion(true, true, null, nodeVersion, stepCall), true));

        QueueTaskFuture<WorkflowRun> future = job.scheduleBuild2(0);

        WorkflowRun run = future.get();

        return j.waitForCompletion(run);
    }

    private void assertNoInstallLeftovers() throws Exception {
        File workspaceRoot = new File(j.jenkins.getRootDir(), "workspace");

        if (!workspaceRoot.isDirectory()) {
            return;
        }

        try (Stream<java.nio.file.Path> paths = Files.walk(workspaceRoot.toPath(), 3)) {
            boolean leftover = paths.filter(path -> path.getFileName() != null)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("cognium") && name.endsWith("dir"));

            assertFalse(leftover, "temporary cognium install directory leaked under " + workspaceRoot);
        }
    }
}
