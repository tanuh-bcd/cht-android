package org.medicmobile.webapp.mobile;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.medicmobile.webapp.mobile.BuildConfig.DEBUG;
import static org.medicmobile.webapp.mobile.MedicLog.error;
import static org.medicmobile.webapp.mobile.MedicLog.log;
import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;
import static org.medicmobile.webapp.mobile.SimpleJsonClient2.redactUrl;
import static org.medicmobile.webapp.mobile.Utils.createUseragentFrom;
import static org.medicmobile.webapp.mobile.Utils.isValidNavigationUrl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings({ "PMD.GodClass", "PMD.TooManyMethods" })
public class EmbeddedBrowserActivity extends Activity {

	private WebView container;
	private SettingsStore settings;
	private String appUrl;
	private MrdtSupport mrdt;
	private FilePickerHandler filePickerHandler;
	private SmsSender smsSender;
	private ChtExternalAppHandler chtExternalAppHandler;
	private boolean isMigrationRunning = false;
	private MobileVitV2Engine mobileVitV2Engine;
	private ExecutorService mobileVitExecutor;
	/** Previous WebView URL; used to clear ML sessionStorage when leaving the oral cancer form. */
	private String lastWebViewUrlForOralMl;

	/**
	 * MobileViTV2 runs only while this CHT form is active (matched as a substring of the WebView URL).
	 */
	private static final String MOBILEVIT_TARGET_FORM_ID = "oral_cancer_assessment";

	private static final ValueCallback<String> IGNORE_RESULT = new ValueCallback<String>() {
		public void onReceiveValue(String result) { /* ignore */ }
	};
	private final ValueCallback<String> backButtonHandler = new ValueCallback<String>() {
		public void onReceiveValue(String result) {
			if(!"true".equals(result)) {
				EmbeddedBrowserActivity.this.moveTaskToBack(false);
			}
		}
	};


//> ACTIVITY LIFECYCLE METHODS
	@SuppressLint("ClickableViewAccessibility")
	@Override public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		trace(this, "Starting webview...");

		this.filePickerHandler = new FilePickerHandler(this);
		this.mobileVitV2Engine = new MobileVitV2Engine(this);
		this.mobileVitExecutor = Executors.newSingleThreadExecutor();
		this.filePickerHandler.setOnCompressedImageReady(uri -> runMobileVitClassification(uri));
		this.mrdt = new MrdtSupport(this);
		this.chtExternalAppHandler = new ChtExternalAppHandler(this);

		try {
			this.smsSender = SmsSender.createInstance(this);
		} catch(Exception ex) {
			error(ex, "Failed to create SmsSender.");
		}

		this.settings = SettingsStore.in(this);
		this.appUrl = settings.getAppUrl();

		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.main);
		View webviewContainer = findViewById(R.id.lytWebView);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			ViewCompat.requestApplyInsets(webviewContainer.getRootView());
		}

		// Add an alarming red border if using configurable (i.e. dev)
		// app with a medic production server.
		if (settings.allowsConfiguration() && appUrl != null && appUrl.contains("app.medicmobile.org")) {
			webviewContainer.setPadding(10, 10, 10, 10);
			webviewContainer.setBackgroundResource(R.drawable.warning_background);
		}

		// Add a noticeable border to easily identify a training app
		if (BuildConfig.IS_TRAINING_APP) {
			webviewContainer.setPadding(10, 10, 10, 10);
			webviewContainer.setBackgroundResource(R.drawable.training_background);
		}

		container = findViewById(R.id.wbvMain);

		getFragmentManager()
			.beginTransaction()
			.add(new OpenSettingsDialogFragment(), OpenSettingsDialogFragment.class.getName())
			.commit();

		configureUserAgent();

		setUpUiClient(container);
		enableRemoteChromeDebugging();
		enableJavascript(container);
		enableStorage(container);

		enableUrlHandlers(container);

		if (settings.allowsConfiguration()) {
			toast(redactUrl(appUrl));
		}

		registerRetryConnectionBroadcastReceiver();

		initializeNotifications();

		String recentNavigation = settings.getLastUrl();
		Intent appLinkIntent = getIntent();
		Uri appLinkData = appLinkIntent.getData();
		if (appLinkData != null) {
			// The app has been opened via an app link.
			browseTo(appLinkData);
		} else if (isValidNavigationUrl(appUrl, recentNavigation)) {
			// The app has been opened normally, and the user can start where they left off.
			container.loadUrl(recentNavigation);
		} else {
			// The app has been opened normally, but no previous URL is available. (Maybe it is the first time.)
			browseTo(null);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		Uri appLinkData = intent.getData();
		browseTo(appLinkData);
	}

	@SuppressWarnings("PMD.CallSuperFirst")
	@Override
	protected void onStart() {
		trace(this, "onStart() :: Checking Crosswalk migration ...");
		XWalkMigration xWalkMigration = new XWalkMigration(this.getApplicationContext());
		if (xWalkMigration.hasToMigrate()) {
			log(this, "onStart() :: Running Crosswalk migration ...");
			isMigrationRunning = true;
			Intent intent = new Intent(this, UpgradingActivity.class)
				.putExtra("isClosable", false)
				.putExtra("backPressedMessage", getString(R.string.waitMigration));
			startActivity(intent);
			xWalkMigration.run();
		} else {
			trace(this, "onStart() :: Crosswalk installation not found - skipping migration");
		}
		trace(this, "onStart() :: Checking Crosswalk migration done.");

		if (BuildConfig.IS_TRAINING_APP) {
			toast(getString(R.string.usingTrainingApp));
		}

		super.onStart();
	}

	@Override
	protected void onStop() {
		String recentNavigation = container.getUrl();
		if (isValidNavigationUrl(appUrl, recentNavigation)) {
			try {
				settings.setLastUrl(recentNavigation);
			} catch (SettingsException e) {
				error(e, "Error recording last URL loaded");
			}
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		if (mobileVitExecutor != null) {
			mobileVitExecutor.shutdown();
		}
		if (mobileVitV2Engine != null) {
			mobileVitV2Engine.close();
		}
		super.onDestroy();
	}

	@Override public void onBackPressed() {
		trace(this, "onBackPressed()");
		container.evaluateJavascript(
				"angular.element(document.body).injector().get('AndroidApi').v1.back()",
				backButtonHandler);
	}

	@Override
	protected void onActivityResult(int requestCd, int resultCode, Intent intent) {
		Optional<RequestCode> requestCodeOpt = RequestCode.valueOf(requestCd);

		if (!requestCodeOpt.isPresent()) {
			trace(this, "onActivityResult() :: no handling for requestCode=%s", requestCd);
			return;
		}

		RequestCode requestCode = requestCodeOpt.get();

		try {
			trace(this, "onActivityResult() :: requestCode=%s, resultCode=%s", requestCode.name(), resultCode);

			switch (requestCode) {
				case FILE_PICKER_ACTIVITY:
					this.filePickerHandler.processResult(resultCode, intent);
					return;
				case GRAB_MRDT_PHOTO_ACTIVITY:
					processMrdtResult(requestCode, intent);
					return;
				case CHT_EXTERNAL_APP_ACTIVITY:
					processChtExternalAppResult(resultCode, intent);
					return;
				case ACCESS_STORAGE_PERMISSION:
					processStoragePermissionResult(resultCode, intent);
					return;
				case ACCESS_LOCATION_PERMISSION:
					locationRequestResolved();
					return;
				case ACCESS_SEND_SMS_PERMISSION:
					this.smsSender.resumeProcess(resultCode);
					return;
				default:
					trace(this, "onActivityResult() :: no handling for requestCode=%s", requestCode.name());
			}
		} catch (Exception ex) {
			String action = intent == null ? null : intent.getAction();
			warn(ex, "Problem handling intent %s (%s) with requestCode=%s & resultCode=%s",
				intent, action, requestCode.name(), resultCode);
		}
	}


	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == AppNotificationManager.REQUEST_NOTIFICATION_PERMISSION && grantResults.length > 0 &&
				grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			initializeNotifications();
		}
	}

	private void runMobileVitClassification(Uri imageUri) {
		if (!isMobileVitTargetFormUrl()) {
			return;
		}
		if (mobileVitV2Engine == null || !mobileVitV2Engine.isAvailable()) {
			trace(this, "MobileVitV2 :: skipped (place models in assets/ml to enable)");
			return;
		}
		mobileVitExecutor.execute(() -> {
			try {
				MobileVitV2Engine.MultiClassificationResult result = mobileVitV2Engine.classify(imageUri);
				runOnUiThread(() -> dispatchMobileVitPredictionToWebView(imageUri, result));
			} catch (IOException e) {
				warn(this, "MobileVitV2 :: classification failed: %s", e.getMessage());
			}
		});
	}

	/**
	 * True when the embedded app URL indicates the oral cancer assessment form is open.
	 */
	private boolean isMobileVitTargetFormUrl() {
		if (container == null) {
			return false;
		}
		String url = container.getUrl();
		return url != null && url.contains(MOBILEVIT_TARGET_FORM_ID);
	}

	private void dispatchMobileVitPredictionToWebView(Uri imageUri, MobileVitV2Engine.MultiClassificationResult multiResult) {
		try {
			JSONObject detail = new JSONObject();
			detail.put("imageUri", imageUri.toString());
			detail.put("isSuspicious", multiResult.isSuspicious);
			detail.put("formId", MOBILEVIT_TARGET_FORM_ID);
			if (container != null) {
				detail.put("pageUrl", container.getUrl());
			}

			JSONArray modelScores = new JSONArray();
			StringBuilder toastMsg = new StringBuilder();
			toastMsg.append(multiResult.isSuspicious ? "AI RESULT: SUSPICIOUS" : "AI RESULT: NORMAL");
			toastMsg.append("\nIndividual Model Probabilities:");

			for (Map.Entry<String, MobileVitV2Engine.ClassificationResult> entry : multiResult.modelResults.entrySet()) {
				String modelPath = entry.getKey();
				MobileVitV2Engine.ClassificationResult res = entry.getValue();
				
				// Assuming index 1 is suspicious, or use probs[0] if single output
				double prob = (res.probs.length > 1) ? res.probs[1] : res.probs[0];
				
				JSONObject m = new JSONObject();
				m.put("model", modelPath);
				m.put("probability", prob);
				m.put("label", res.label);
				modelScores.put(m);

				String modelName = modelPath.substring(modelPath.lastIndexOf('/') + 1).replace(".onnx", "");
				toastMsg.append(String.format(java.util.Locale.UK, "\n• %s: %.4f", modelName, prob));
			}
			detail.put("modelScores", modelScores);

			// Log to Logcat
			log(this, "MobileVitV2 :: Inference complete. isSuspicious=%s", multiResult.isSuspicious);

			// Display visual toast
//			toast(toastMsg.toString());

			String script =
				"(function(){var d=" + detail.toString() + ";" +
				"try{" +
				"window.dispatchEvent(new CustomEvent('cht-mobilevit-prediction',{detail:d}));" +
				"if(typeof window.onChtMobileVitPrediction==='function'){window.onChtMobileVitPrediction(d);}" +
				"}catch(e){console.error(e);}" +
				oralCancerAssessmentFormFillJavascript() +
				"})();";
			evaluateJavascript(script, false);
		} catch (JSONException e) {
			warn(this, "MobileVitV2 :: %s", e.getMessage());
		}
	}

	/**
	 * Fills v3 paginated form paths: {@code /data/photo_N_page/ai_photo_N_label|score}, {@code /data/group_summary/ai_analysis_*}.
	 * Slot N is taken from {@code d.pageUrl} when it matches {@code photo_N_page}, else round-robin.
	 */
	private static String oralCancerAssessmentFormFillJavascript() {
		return
			"try{" +
			"(function(){" +
			"function chtSetXformPath(path,val){" +
			"if(val===undefined||val===null)return;" +
			"var leaf = path.split('/').pop();" +
			"var pathNoSlash = path.startsWith('/') ? path.substring(1) : path;" +
			"var pathEnketo = 'data' + path.split('/').filter(function(x){return !!x && x!=='data';}).map(function(x){return '['+x+']';}).join('');" +
			"var selectors = [" +
			" '[name=\"'+path+'\"]'," +
			" '[name=\"'+pathNoSlash+'\"]'," +
			" '[name=\"'+leaf+'\"]'," +
			" '[name$=\"/'+leaf+'\"]'," +
			" '[name=\"'+pathEnketo+'\"]'," +
			" '[name$=\"['+leaf+']\"]'" +
			"];" +
			"var el = null; for(var i=0; i<selectors.length; i++){ el = document.querySelector(selectors[i]); if(el) break; }" +
			"if(el){el.value=val;el.dispatchEvent(new Event('input',{bubbles:true}));" +
			"el.dispatchEvent(new Event('change',{bubbles:true}));" +
			"console.log('MobileVitV2 :: Set field '+path+' to '+val+' using selector '+selectors[i]); return;}" +
			"if(window.jQuery){" +
			"var $el = window.jQuery(selectors.join(','));" +
			"if($el.length){$el.val(val).trigger('change');" +
			"console.log('MobileVitV2 :: Set field '+path+' to '+val+' (jQuery)'); return;}" +
			"}" +
			"console.warn('MobileVitV2 :: Could not find field for path: '+path);" +
			"}" +
			"function inferSlotFromUrl(u){" +
			"if(!u)return 0;" +
			"var m=u.match(/photo[_/](\\\\d+)[_/]page/i)||u.match(/photo[_-]?(\\\\d+)[_/]page/i);" +
			"if(m)return parseInt(m[1],10);" +
			"m=u.match(/[?&#]p(?:age)?[=:](\\\\d+)/i);if(m)return parseInt(m[1],10);" +
			"m=u.match(/" + "\\/page\\/" + "(\\\\d+)\\\\b/i);if(m)return parseInt(m[1],10);" +
			"return 0;}" +
			"var storageKey='cht_oral_ca_ml_photo_idx';" +
			"var slotUrl=inferSlotFromUrl(d.pageUrl||'');" +
			"var n=0;" +
			"if(slotUrl>=1&&slotUrl<=8){n=slotUrl;sessionStorage.setItem(storageKey,String(n));}" +
			"else{" +
			"var idx=parseInt(sessionStorage.getItem(storageKey)||'0',10);" +
			"if(isNaN(idx)||idx<0)idx=0;" +
			"if(idx>=8)idx=0;" +
			"n=idx+1;" +
			"sessionStorage.setItem(storageKey,String(idx+1));" +
			"}" +
			"var base='/data/photo_'+n+'_page/';" +
			"chtSetXformPath(base+'ai_photo_'+n+'_suspicion',d.isSuspicious?'suspicious':'non_suspicious');" +
			"var countKey='cht_oral_ca_ml_done_count';" +
			"var done=parseInt(sessionStorage.getItem(countKey)||'0',10);" +
			"if(done<8)sessionStorage.setItem(countKey,String(done+1));" +
			"done=Math.min(8,done+1);" +
			"var gsum='/data/group_summary/';" +
			"chtSetXformPath(gsum+'ai_analysis_status','Analyzed '+done+'/8 photos (latest: photo '+n+')');" +
			"var sumKey='cht_oral_ca_ml_summary_lines';" +
			"var lines=[];try{lines=JSON.parse(sessionStorage.getItem(sumKey)||'[]');}catch(e2){lines=[];}" +
			"if(!Array.isArray(lines))lines=[];" +
			"var scoresSummary=d.modelScores.map(function(s){ " +
			"  var name = s.model.substring(s.model.lastIndexOf('/')+1).replace('.onnx','');" +
			"  return name + ': ' + s.probability.toFixed(4);" +
			"}).join(' | ');" +
			"var line='Photo '+n+': '+(d.isSuspicious?'SUSPICIOUS':'Normal')+' ['+scoresSummary+']';" +
			"lines.push(line);" +
			"if(lines.length>16)lines=lines.slice(-16);" +
			"sessionStorage.setItem(sumKey,JSON.stringify(lines));" +
			"var diagnosisKey='cht_oral_ca_any_suspicious';" +
			"var currentDiagnosis=(sessionStorage.getItem(diagnosisKey)==='true')||d.isSuspicious;" +
			"sessionStorage.setItem(diagnosisKey,String(currentDiagnosis));" +
			"var summaryText=lines.join('\\n');" +
			"if(done>=8){" +
			"  summaryText+='\\n\\n\\nFINAL RESULT: '+(currentDiagnosis?'SUSPICIOUS':'NORMAL');" +
			"}" +
			"chtSetXformPath(gsum+'ai_analysis_summary',summaryText);" +
			"chtSetXformPath(gsum+'ai_final_diagnosis',currentDiagnosis?'suspicious':'non_suspicious');" +
			"if(done>=8){" +
			"  chtSetXformPath('/data/final_ai_analysis_page/final_ai_suspicion',currentDiagnosis?'suspicious':'non_suspicious');" +
			"}" +
			"chtSetXformPath(gsum+'ai_analysis_error','');" +
			"})();" +
			"}catch(e3){console.error(e3);}";
	}

	/**
	 * Clears ML round-robin / summary keys in {@code sessionStorage}. Call when the form is closed or from JS
	 * {@code medicmobile_android.clearOralCancerMlSessionStorage()}.
	 */
	public void clearOralCancerMlSessionStorage() {
		evaluateJavascript(
			"(function(){var k=['cht_oral_ca_ml_photo_idx','cht_oral_ca_ml_done_count','cht_oral_ca_ml_summary_lines','cht_oral_ca_explicit_slot','cht_oral_ca_any_suspicious'];"
				+ "k.forEach(function(x){try{sessionStorage.removeItem(x);}catch(e){}});})();",
			false);
	}

	void onWebViewPageFinishedForMobileVit(String url) {
		String prev = this.lastWebViewUrlForOralMl;
		this.lastWebViewUrlForOralMl = url;
		if (prev != null && prev.contains(MOBILEVIT_TARGET_FORM_ID) && url != null && !url.contains(MOBILEVIT_TARGET_FORM_ID)) {
			clearOralCancerMlSessionStorage();
		}
	}

	private void initializeNotifications() {
		AppNotificationManager appNotificationManager = new AppNotificationManager(this);
		appNotificationManager.cancelAllNotifications();
		if (!appNotificationManager.hasNotificationPermission()) {
			appNotificationManager.requestNotificationPermission(this);
			appNotificationManager.stopNotificationWorker();
			return;
		}
		appNotificationManager.startNotificationWorker();
	}

//> ACCESSORS
	MrdtSupport getMrdtSupport() {
		return this.mrdt;
	}

	SmsSender getSmsSender() {
		return this.smsSender;
	}

	ChtExternalAppHandler getChtExternalAppHandler() {
		return this.chtExternalAppHandler;
	}

//> PUBLIC API
	public void evaluateJavascript(final String js) {
		evaluateJavascript(js, true);
	}

	public void evaluateJavascript(final String js, final boolean useLoadUrl) {
		int maxUrlSize = 2097100; // Maximum character limit supported for loading as url.

		if (useLoadUrl && js.length() <= maxUrlSize) {
			// `WebView.loadUrl()` seems to be significantly faster than `WebView.evaluateJavascript()` on Tecno Y4.
			container.post(() -> container.loadUrl("javascript:" + js, null));
		} else {
			container.post(() -> container.evaluateJavascript(js, IGNORE_RESULT));
		}
	}

	public void errorToJsConsole(String message, Object... extras) {
		String formatted = String.format(message, extras);
		String escaped = formatted.replace("'", "\\'");
		evaluateJavascript("console.error('" + escaped + "');");
	}

	public boolean isMigrationRunning() {
		return isMigrationRunning;
	}

	public void setMigrationRunning(boolean migrationRunning) {
		isMigrationRunning = migrationRunning;
	}

	public boolean getLocationPermissions() {
		boolean hasFineLocation = ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED;
		boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED;

		if (hasFineLocation && hasCoarseLocation) {
			trace(this, "getLocationPermissions() :: Fine and Coarse location already granted");
			return true;
		}

		trace(this, "getLocationPermissions() :: Fine or Coarse location not granted before, requesting access...");
		startActivityForResult(
			new Intent(this, RequestLocationPermissionActivity.class),
			RequestCode.ACCESS_LOCATION_PERMISSION.getCode()
		);
		return false;
	}

//> PRIVATE HELPERS
	private void locationRequestResolved() {
		evaluateJavascript("window.CHTCore.AndroidApi.v1.locationPermissionRequestResolved();");
	}

	private void processChtExternalAppResult(int resultCode, Intent intentData) {
		String script = this.chtExternalAppHandler.processResult(resultCode, intentData);
		trace(this, "ChtExternalAppHandler :: Executing JavaScript: %s", script);
		evaluateJavascript(script);
	}

	private void processMrdtResult(RequestCode requestCode, Intent intent) {
		String js = mrdt.process(requestCode, intent);
		trace(this, "Executing JavaScript: %s", js);
		evaluateJavascript(js);
	}

	private void processStoragePermissionResult(int resultCode, Intent intent) {
		String triggerClass = intent == null ? null : intent.getStringExtra(RequestStoragePermissionActivity.TRIGGER_CLASS);

		if (FilePickerHandler.class.getName().equals(triggerClass)) {
			trace(this, "EmbeddedBrowserActivity :: Resuming FilePickerHandler process. Trigger:%s", triggerClass);
			this.filePickerHandler.resumeProcess(resultCode);
			return;
		}

		if (ChtExternalAppHandler.class.getName().equals(triggerClass)) {
			trace(this, "EmbeddedBrowserActivity :: Resuming ChtExternalAppHandler activity. Trigger:%s", triggerClass);
			this.chtExternalAppHandler.resumeActivity(resultCode);
			return;
		}

		trace(
			this,
			"EmbeddedBrowserActivity :: No handling for trigger: %s, requestCode: %s",
			triggerClass,
			RequestCode.ACCESS_STORAGE_PERMISSION.name()
		);
	}

	private void configureUserAgent() {
		String current = WebSettings.getDefaultUserAgent(this);
		container.getSettings().setUserAgentString(createUseragentFrom(current));
	}

	private void browseTo(Uri url) {
		String urlToLoad = this.settings.getUrlToLoad(url);
		trace(this, "Pointing browser to: %s", redactUrl(urlToLoad));
		container.loadUrl(urlToLoad, null);
	}

	private void enableRemoteChromeDebugging() {
		WebView.setWebContentsDebuggingEnabled(true);
	}

	private void setUpUiClient(WebView container) {
		container.setWebChromeClient(new WebChromeClient() {
			@Override public boolean onConsoleMessage(ConsoleMessage cm) {
				if (!DEBUG) {
					return super.onConsoleMessage(cm);
				}
				trace(this, "onConsoleMessage() :: %s:%s | %s", cm.sourceId(), cm.lineNumber(), cm.message());
				return true;
			}

			@Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
				filePickerHandler.openPicker(fileChooserParams, filePathCallback);
				return true;
			}

			@Override public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
				callback.invoke(origin, true, true);
			}
		});
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void enableJavascript(WebView container) {
		container.getSettings().setJavaScriptEnabled(true);

		MedicAndroidJavascript maj = new MedicAndroidJavascript(this);
		maj.setAlert(new Alert(this));

		maj.setActivityManager((ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE));

		maj.setConnectivityManager((ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE));

		container.addJavascriptInterface(maj, "medicmobile_android");
	}

	private void enableStorage(WebView container) {
		WebSettings settings = container.getSettings();
		settings.setDomStorageEnabled(true);
		settings.setDatabaseEnabled(true);
	}

	private void enableUrlHandlers(WebView container) {
		container.setWebViewClient(new UrlHandler(this, settings) {
			@Override public void onPageFinished(WebView view, String url) {
				super.onPageFinished(view, url);
				EmbeddedBrowserActivity.this.onWebViewPageFinishedForMobileVit(url);
				injectConsoleObjectLogger();
			}
		});
	}

	private void injectConsoleObjectLogger() {
		String script =
			"(function() {" +
			"  if (window.__chtConsolePatched) { return; }" +
			"  window.__chtConsolePatched = true;" +
			"  function stringifyArg(arg) {" +
			"    if (typeof arg === 'undefined') { return 'undefined'; }" +
			"    if (arg === null) { return 'null'; }" +
			"    if (typeof arg === 'string') { return arg; }" +
			"    if (arg instanceof Error) {" +
			"      return JSON.stringify({" +
			"        name: arg.name," +
			"        message: arg.message," +
			"        stack: arg.stack" +
			"      });" +
			"    }" +
			"    try { return JSON.stringify(arg); } catch (e) { return String(arg); }" +
			"  }" +
			"  ['log', 'warn', 'error', 'info', 'debug'].forEach(function(level) {" +
			"    var original = console[level];" +
			"    if (!original) { return; }" +
			"    console[level] = function() {" +
			"      var args = Array.prototype.slice.call(arguments).map(stringifyArg);" +
			"      return original.apply(console, args);" +
			"    };" +
			"  });" +
			"  function utf8Bytes(value) {" +
			"    try {" +
			"      return new TextEncoder().encode(value).length;" +
			"    } catch (e) {" +
			"      return String(value).length;" +
			"    }" +
			"  }" +
			"  function estimateBodyBytes(body) {" +
			"    if (body == null) { return 0; }" +
			"    if (typeof body === 'string') { return utf8Bytes(body); }" +
			"    if (body instanceof URLSearchParams) { return utf8Bytes(body.toString()); }" +
			"    if (body instanceof FormData) {" +
			"      var total = 0;" +
			"      try {" +
			"        body.forEach(function(value, key) {" +
			"          total += utf8Bytes(key);" +
			"          if (typeof value === 'string') {" +
			"            total += utf8Bytes(value);" +
			"            return;" +
			"          }" +
			"          if (value && typeof value.size === 'number') {" +
			"            total += value.size;" +
			"            return;" +
			"          }" +
			"          total += utf8Bytes(String(value));" +
			"        });" +
			"        return total;" +
			"      } catch (e) {" +
			"        return -1;" +
			"      }" +
			"    }" +
			"    if (body instanceof Blob) { return body.size; }" +
			"    if (body instanceof ArrayBuffer) { return body.byteLength; }" +
			"    if (ArrayBuffer.isView(body)) { return body.byteLength; }" +
			"    if (typeof body === 'object') {" +
			"      try { return utf8Bytes(JSON.stringify(body)); } catch (e) { return -1; }" +
			"    }" +
			"    return utf8Bytes(String(body));" +
			"  }" +
			"  function shouldLogPayload(url, method, size) {" +
			"    var isWrite = method === 'POST' || method === 'PUT';" +
			"    var looksLikeReplication = /_bulk_docs|_revs_diff|_bulk_get|_changes/.test(url || '');" +
			"    if (!isWrite) { return false; }" +
			"    if (looksLikeReplication) { return true; }" +
			"    return size > 100 * 1024;" +
			"  }" +
			"  function logPayload(url, method, size) {" +
			"    if (size < 0) {" +
			"      console.warn('[payload-size] method=' + method + ' url=' + url + ' bytes=unknown');" +
			"      return;" +
			"    }" +
			"    console.warn('[payload-size] method=' + method + ' url=' + url + ' bytes=' + size);" +
			"  }" +
			"  if (window.fetch) {" +
			"    var originalFetch = window.fetch;" +
			"    window.fetch = function(input, init) {" +
			"      var requestUrl = typeof input === 'string' ? input : (input && input.url ? input.url : '');" +
			"      var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();" +
			"      var body = init && Object.prototype.hasOwnProperty.call(init, 'body') ? init.body : (input && input.body);" +
			"      var size = estimateBodyBytes(body);" +
			"      if (shouldLogPayload(requestUrl, method, size)) { logPayload(requestUrl, method, size); }" +
			"      return originalFetch.apply(this, arguments);" +
			"    };" +
			"  }" +
			"  if (window.XMLHttpRequest) {" +
			"    var xhrOpen = XMLHttpRequest.prototype.open;" +
			"    var xhrSend = XMLHttpRequest.prototype.send;" +
			"    XMLHttpRequest.prototype.open = function(method, url) {" +
			"      this.__chtMethod = (method || 'GET').toUpperCase();" +
			"      this.__chtUrl = url || '';" +
			"      return xhrOpen.apply(this, arguments);" +
			"    };" +
			"    XMLHttpRequest.prototype.send = function(body) {" +
			"      var size = estimateBodyBytes(body);" +
			"      if (shouldLogPayload(this.__chtUrl, this.__chtMethod || 'GET', size)) {" +
			"        logPayload(this.__chtUrl, this.__chtMethod || 'GET', size);" +
			"      }" +
			"      return xhrSend.apply(this, arguments);" +
			"    };" +
			"  }" +
			"})();";
		evaluateJavascript(script, false);
	}

	private void toast(String message) {
		if (message != null) {
			runOnUiThread(() -> {
				Toast.makeText(EmbeddedBrowserActivity.this, message, Toast.LENGTH_LONG).show();
			});
		}
	}

	private void registerRetryConnectionBroadcastReceiver() {
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (action.equals("retryConnection")) {
					// user fixed the connection and asked the app
					// to retry the load from the connection error activity
					evaluateJavascript("window.location.reload()", false);
				}
			}
		};
		ContextCompat.registerReceiver(
			getApplicationContext(),
			broadcastReceiver,
			new IntentFilter("retryConnection"),
			ContextCompat.RECEIVER_NOT_EXPORTED
		);
	}

//> ENUMS
	public enum RequestCode {
		ACCESS_LOCATION_PERMISSION(100),
		ACCESS_STORAGE_PERMISSION(101),
		ACCESS_SEND_SMS_PERMISSION(102),
		CHT_EXTERNAL_APP_ACTIVITY(103),
		GRAB_MRDT_PHOTO_ACTIVITY(104),
		FILE_PICKER_ACTIVITY(105);

		private final int requestCode;

		RequestCode(int requestCode) {
			this.requestCode = requestCode;
		}

		public static Optional<RequestCode> valueOf(int code) {
			return Arrays
				.stream(RequestCode.values())
				.filter(e -> e.getCode() == code)
				.findFirst();
		}

		public int getCode() {
			return requestCode;
		}
	}

}
