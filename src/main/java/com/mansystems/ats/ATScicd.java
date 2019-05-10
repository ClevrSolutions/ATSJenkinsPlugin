package com.mansystems.ats;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ATScicd {

	static interface ATSLogger {
		public void log(String message);
	}
	
	private final String appId;
	private final String apiToken;
	private final String baseURL;
	private final ATSLogger logger;
	
	public ATScicd(String appId, String apiToken, String baseURL, ATSLogger logger) {
		this.appId = appId;
		this.apiToken = apiToken;
		this.baseURL = baseURL;
		this.logger = logger;
	}
	
	/**
	 * Returns true if all tests pass
	 * @param jobTemplateId - specified in ATS
	 * @param rerun - when true, any failing tests will be rerun 2 times. If both times all tests pass then the job is considered passed.
	 * @return true if the tests pass
	 * @throws Exception
	 */
	public boolean runTestAndGetResult(String jobTemplateId, boolean rerun) throws Exception {
		logger.log("ATS: running tests for template |"+jobTemplateId+"|");
		RunJobResponse result = Requests.runJob(appId, apiToken, jobTemplateId, baseURL);
		if ( result.started ) {
			logger.log("ATS:   tests started with job id: |"+result.jobId+"|");
			GetTestRunResponse test_run_response = getResultForJob(baseURL, result.jobId);
			if ( test_run_response == null || ! test_run_response.done ) 
				throw new Exception("Timed out while waiting for test result for job |"+result.jobId+"|");
			if ( test_run_response.passed ) return true;
			else if ( ! rerun ) return false;
			else {
				logger.log("ATS:   not all tests passed. not passing test cases will be rerun automatically up to 2 times.");

				boolean rerun1_passed = rerunNotPassed(baseURL, result.jobId, 1);
				if ( ! rerun1_passed ) return false;
				boolean rerun2_passed = rerunNotPassed(baseURL, result.jobId, 2);
				return rerun2_passed;
			}
		}
		else
			throw new Exception("Error while trying to start tests: "+result.errorMessage);
	}

	private boolean rerunNotPassed(String baseURL, String jobId, int i)
			throws Exception {
		RunJobResponse rerun_response = Requests.rerunNotPassed(appId, apiToken, jobId, baseURL);
		if ( rerun_response == null || ! rerun_response.started) 
			throw new Exception("Rerun "+i+" failed to start. "+
						          (rerun_response!=null?rerun_response.errorMessage:""));
		logger.log("ATS:      rerun "+i+" successfully started with id: |"+rerun_response.jobId+"|");
		GetTestRunResponse result = getResultForJob(baseURL, rerun_response.jobId);
		if ( result == null || ! result.done) 
			throw new Exception("Rerun "+i+" failed to retrieve result. "+
						          (result!=null?result.errorMessage:""));
		logger.log("ATS:      rerun "+i+" is "+(result.passed?"":"NOT ")+"passed.");
		return result.passed;
	}

	/**
	 * Gets the result for a job by querying ATS repeatedly until the job status is Done, sleeping in between.
	 * @param baseURL
	 * @param jobId
	 * @return null if timed out
	 */
	private GetTestRunResponse getResultForJob(String baseURL, String jobId)
			throws InterruptedException, MalformedURLException, IOException, ProtocolException {
		Thread.sleep(3000);
		GetTestRunResponse test_run_response = null;
		for ( int i = 0 ; i < 40 + 40 + 270 ; ++i ) {
			test_run_response = Requests.getJobStatus(appId, apiToken, jobId, baseURL);
			//logger.log("Successfully retrieved result. Job is "+(test_run_response.done?"":"NOT ")+"DONE.");
			int                ms_to_wait = 15000; //query every 15 seconds for the first 10 minutes i.e. 4*10 = 40 iterations
			if ( i > 40 )      ms_to_wait = 30000; //then every 30 seconds for the next 20 minutes i.e. 2*10 = 40 iterations
			if ( i > 40 + 40 ) ms_to_wait = 60000; //then query every 60 seconds up to 6 hours i.e. 270 iterations
			
			Thread.sleep(ms_to_wait);
			
			if ( test_run_response.done ) break;
		}
		return test_run_response;
	}

	
}

class RunJobResponse {
	String errorMessage;
	boolean started;
	String jobId;
	
	private RunJobResponse(String errorMessage, boolean started, String jobId) {
		this.errorMessage = errorMessage;
		this.started = started;
		this.jobId = jobId;
	}

	private static final Pattern ERROR_MSG_PATTERN = Pattern.compile("\\Q<ErrorMessage><![CDATA[\\E(.*?)\\Q]]></ErrorMessage>\\E", Pattern.DOTALL);
	private static final  Pattern JOB_ID_PATTERN = Pattern.compile("\\Q<JobID><![CDATA[\\E(.*?)\\Q]]></JobID>\\E", Pattern.DOTALL);
	
	static RunJobResponse parseResponse(String response) {
		String errorMsg = Utils.getFirstMatchGroupOrNull(response, ERROR_MSG_PATTERN);
		if ( errorMsg != null ) return new RunJobResponse(errorMsg, false, null);
		String jobId = Utils.getFirstMatchGroupOrNull(response, JOB_ID_PATTERN);
		return new RunJobResponse(null, true, jobId);
	}
}

class GetTestRunResponse {
	boolean done;
	boolean passed;
	String errorMessage;
	
	private GetTestRunResponse(String errorMessage, boolean done, boolean passed) {
		this.errorMessage = errorMessage;
		this.done = done;
		this.passed = passed;
	}
	
	private static final Pattern ERROR_MSG_PATTERN = Pattern.compile("\\Q<ErrorMessage><![CDATA[\\E(.*?)\\Q]]></ErrorMessage>\\E", Pattern.DOTALL);
	private static final  Pattern EXECUTION_STATUS_PATTERN = Pattern.compile("\\Q<ExecutionStatus><![CDATA[\\E(.*?)\\Q]]></ExecutionStatus>\\E", Pattern.DOTALL);
	private static final  Pattern EXECUTION_RESULT_PATTERN = Pattern.compile("\\Q<ExecutionResult><![CDATA[\\E(.*?)\\Q]]></ExecutionResult>\\E", Pattern.DOTALL);
	
	static GetTestRunResponse parseResponse(String response) {
		String errorMsg = Utils.getFirstMatchGroupOrNull(response, ERROR_MSG_PATTERN);
		boolean done = "Done".equals(Utils.getFirstMatchGroupOrNull(response, EXECUTION_STATUS_PATTERN));
		boolean passed = "Passed".equals(Utils.getFirstMatchGroupOrNull(response, EXECUTION_RESULT_PATTERN));
		return new GetTestRunResponse(errorMsg, done, passed);
	}
}

class Requests {

	static RunJobResponse runJob(String appId, String apiToken, String jobTemplateId, String baseURL)
			throws MalformedURLException, IOException, ProtocolException {

		String request = RequestTemplates.RUN_JOB
							.replace("${AppId}"        , appId)
							.replace("${AppAPIToken}"  , apiToken)
							.replace("${JobTemplateID}", jobTemplateId);

		String response = Utils.sendPostRequest(new URL(baseURL+"/ws/RunJob"), request);
		RunJobResponse result = RunJobResponse.parseResponse(response);
		return result;
	}
	
	static GetTestRunResponse getJobStatus(String appId, String apiToken, String jobId, String baseURL)
			throws MalformedURLException, IOException, ProtocolException {

		String request = RequestTemplates.GET_JOB_STATUS
							.replace("${AppId}"        , appId)
							.replace("${AppAPIToken}"  , apiToken)
							.replace("${JobID}"        , jobId);
		String response = Utils.sendPostRequest(new URL(baseURL+"/ws/GetJobStatus"), request);
		
		return GetTestRunResponse.parseResponse(response);
	}
	
	static RunJobResponse rerunNotPassed(String appId, String apiToken, String jobId, String baseURL)
			throws MalformedURLException, IOException {

		String request = RequestTemplates.RERUN_NOT_PASSED
							.replace("${AppId}"        , appId)
							.replace("${AppAPIToken}"  , apiToken)
							.replace("${FinishedJobID}", jobId);
		String response = Utils.sendPostRequest(new URL(baseURL+"/ws/RerunNotPassed"), request);

		return RunJobResponse.parseResponse(response);
	}
}

class Utils {
	
	
	static String  getFirstMatchGroupOrNull(String text, Pattern pattern) {
		Matcher matcher = pattern.matcher(text);
		if ( matcher.find() )
			return matcher.group(1);
		return null;
	}
	
	static String sendPostRequest(URL url,String body) throws IOException {
		HttpURLConnection con = (HttpURLConnection) url.openConnection();

		con.setRequestMethod("POST");
		con.setRequestProperty("Content-Type","application/xml");
		Utils.setBody(con,  body);
		con.connect();
		return Utils.getResponseAsString(con);
	}
	
	private static String getResponseAsString(HttpURLConnection con) throws IOException {
		BufferedInputStream bis = new BufferedInputStream(con.getInputStream());
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int result2 = bis.read();
		while(result2 != -1) {
		    buf.write((byte) result2);
		    result2 = bis.read();
		}
		return buf.toString();
	}

	private static void setBody(HttpURLConnection con, String request) throws IOException {
		con.setDoOutput(true);
		OutputStreamWriter osw = new OutputStreamWriter(con.getOutputStream());
		osw.write(request);
		osw.flush();
		osw.close();
		con.getOutputStream().close();
	}
	
}

class RequestTemplates {
	final static String GET_JOB_STATUS = 
			"<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:men=\"http://www.mendix.com/\">" + 
			"   <soapenv:Header>n" + 
			"      <men:authentication>" + 
			"         <username>ATSAPIUser</username>" + 
			"         <password>ATSAPIPassword</password>" + 
			"      </men:authentication>" + 
			"   </soapenv:Header>" + 
			"   <soapenv:Body>" + 
			"      <men:GetTestRun>" + 
			"         <TestRun>" + 
			"            <AppAPIToken>${AppAPIToken}</AppAPIToken>" + 
			"            <JobID>${JobID}</JobID>" + 
			"            <AppID>${AppId}</AppID>" + 
			"         </TestRun>" + 
			"      </men:GetTestRun>" + 
			"   </soapenv:Body>" + 
			"</soapenv:Envelope>";

	
	final static String RUN_JOB = 
			"<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:men=\"http://www.mendix.com/\">" + 
			"   <soapenv:Header>" + 
			"      <men:authentication>" + 
			"         <username>ATSAPIUser</username>" + 
			"         <password>ATSAPIPassword</password>" + 
			"      </men:authentication>" + 
			"   </soapenv:Header>" + 
			"   <soapenv:Body>" + 
			"      <men:RunJob>" + 
			"         <TestRun>" + 
			"            <AppAPIToken>${AppAPIToken}</AppAPIToken>" + 
			"            <AppID>${AppId}</AppID>" + 
			"            <JobTemplateID>${JobTemplateID}</JobTemplateID>" + 
			"         </TestRun>" + 
			"      </men:RunJob>" + 
			"   </soapenv:Body>" + 
			"</soapenv:Envelope>";
	
	final static String RERUN_NOT_PASSED = 
			"<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:men=\"http://www.mendix.com/\">\r\n" + 
			"   <soapenv:Header>" + 
			"      <men:authentication>" + 
			"         <username>ATSAPIUser</username>" + 
			"         <password>ATSAPIPassword</password>" + 
			"      </men:authentication>" + 
			"   </soapenv:Header>" + 
			"   <soapenv:Body>" + 
			"      <men:RerunNotPassed>" + 
			"         <RerunNotPassed>" + 
			"            <AppAPIToken>${AppAPIToken}</AppAPIToken>" + 
			"            <AppID>${AppId}</AppID>" + 
			"            <FinishedJobID>${FinishedJobID}</FinishedJobID>" + 
			"         </RerunNotPassed>" + 
			"      </men:RerunNotPassed>" + 
			"   </soapenv:Body>" + 
			"</soapenv:Envelope>";
}
