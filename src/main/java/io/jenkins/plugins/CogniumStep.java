package io.jenkins.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class CogniumStep extends Step {

    private String path = CogniumCli.DEFAULT_PATH;
    private String packageVersion = CogniumCli.DEFAULT_PACKAGE_VERSION;
    private String format = CogniumCli.DEFAULT_FORMAT;
    private String output;
    private String severity;
    private String category;
    private boolean failOnFindings;

    @DataBoundConstructor
    public CogniumStep() {}

    public String getPath() {
        return path;
    }

    @DataBoundSetter
    public void setPath(String path) {
        this.path = CogniumCli.defaultedPath(path);
    }

    public String getPackageVersion() {
        return packageVersion;
    }

    @DataBoundSetter
    public void setPackageVersion(String packageVersion) {
        this.packageVersion = CogniumCli.defaultedPackageVersion(packageVersion);
    }

    public String getFormat() {
        return format;
    }

    @DataBoundSetter
    public void setFormat(String format) {
        this.format = CogniumCli.defaultedFormat(format);
    }

    public String getOutput() {
        return output;
    }

    @DataBoundSetter
    public void setOutput(String output) {
        this.output = CogniumCli.optionalOutput(output);
    }

    public String getSeverity() {
        return severity;
    }

    @DataBoundSetter
    public void setSeverity(String severity) {
        this.severity = CogniumCli.optionalCliValue(severity, "severity");
    }

    public String getCategory() {
        return category;
    }

    @DataBoundSetter
    public void setCategory(String category) {
        this.category = CogniumCli.optionalCliValue(category, "category");
    }

    public boolean isFailOnFindings() {
        return failOnFindings;
    }

    @DataBoundSetter
    public void setFailOnFindings(boolean failOnFindings) {
        this.failOnFindings = failOnFindings;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new CogniumStepExecution(this, context);
    }

    @Extension
    @Symbol("cognium")
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        @NonNull
        public String getFunctionName() {
            return "cognium";
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "Cognium SAST";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }

        @Override
        @NonNull
        public Set<Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, Launcher.class, TaskListener.class, hudson.EnvVars.class);
        }

        public FormValidation doCheckPackageVersion(@QueryParameter String packageVersion) {
            if (Util.fixEmptyAndTrim(packageVersion) == null) {
                return FormValidation.ok();
            }
            try {
                CogniumCli.requireSafeNpmVersion(packageVersion);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckFormat(@QueryParameter String format) {
            return validateOptionalToken(format, "format");
        }

        public FormValidation doCheckSeverity(@QueryParameter String severity) {
            return validateOptionalToken(severity, "severity");
        }

        public FormValidation doCheckCategory(@QueryParameter String category) {
            return validateOptionalToken(category, "category");
        }

        public FormValidation doCheckPath(@QueryParameter String path) {
            try {
                CogniumCli.defaultedPath(path);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckOutput(@QueryParameter String output) {
            try {
                CogniumCli.optionalOutput(output);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        private static FormValidation validateOptionalToken(String value, String fieldName) {
            try {
                CogniumCli.optionalCliValue(value, fieldName);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
