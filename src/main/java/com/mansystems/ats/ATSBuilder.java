package com.mansystems.ats;

import java.io.PrintStream;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
//import hudson.model.PersistentDescriptor;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import net.sf.json.JSONObject;

public class ATSBuilder extends Builder implements SimpleBuildStep {

    private final String appId;
    private final String appAPItoken;
    private final String jobTemplateID;
    private final boolean rerunAutomatically;
    private final String atsURL;

    @DataBoundConstructor
    public ATSBuilder(String appId, String appAPItoken, String jobTemplateID, boolean rerunAutomatically, String atsURL) {
        this.appId = appId;
        this.appAPItoken = appAPItoken;
        this.jobTemplateID = jobTemplateID;
        this.rerunAutomatically = rerunAutomatically;
        this.atsURL = atsURL;
    }


    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        final java.io.PrintStream logger = listener.getLogger();
        try {
            ATScicd ats = new ATScicd(appId, appAPItoken, atsURL,
                            new ATScicd.ATSLogger() {	
                                @Override
                                public void log(String message) {
                                    logger.println(message);
                                }
                            });
            run.setResult(ats.runTestAndGetResult(jobTemplateID, rerunAutomatically) ? Result.SUCCESS : Result.FAILURE);
        }
        catch ( Exception e ) { throw new RuntimeException(e); }
    }

    
    public String getAppId() {
        return appId;
    }

    public String getAppAPItoken() {
        return appAPItoken;
    }

    public String getJobTemplateID() {
        return jobTemplateID;
    }

    public boolean getRerunAutomatically() {
        return rerunAutomatically;
    }

    public String getAtsURL() {
        return atsURL;
    }
    
    @Extension @Symbol("ATS")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        private String appId;

        public String getAppId() {
            return appId;
        }
        
        public void setAppId(String appId) {
            this.appId = appId;
        }

        public FormValidation doCheckappId(@QueryParameter String appId)
                throws IOException, ServletException {
            if (appId.length() == 0)
                return FormValidation.error("Required");
            return FormValidation.ok();
        }

        public FormValidation doCheckappAPItoken(@QueryParameter String appAPItoken)
                throws IOException, ServletException {
            if (appAPItoken.length() == 0)
                return FormValidation.error("Required");
            return FormValidation.ok();
        }

        public FormValidation doCheckjobTemplateID(@QueryParameter String jobTemplateID)
                throws IOException, ServletException {
            if (jobTemplateID.length() == 0)
                return FormValidation.error("Required");
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Run ATS test(s)";
        }

    }


}
