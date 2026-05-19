package org.medicmobile.webapp.mobile;

import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Ensemble MobileViTV2 image classification using multiple ONNX models.
 * <p>
 * Expects models in {@code assets/ml/} and {@code assets/ml/labels.json}.
 * Logic: OR-logic for suspicious marking (any model > 0.5 threshold).
 */
public final class MobileVitV2Engine {

	static final String[] ASSET_MODELS = {
		"ml/mvit2_fold1_6_latest_traced_(model6).onnx",
		"ml/mvit2_fold1_8_traced_(model8).onnx",
		"ml/mvit2_fold2_8_latest_traced_(model8-2).onnx"
	};
	static final String ASSET_LABELS = "ml/labels.json";

	private static final int CROP_SIZE = 256;

	private final Context appContext;
	private final Object sessionLock = new Object();
	private final Map<String, OrtSession> sessions = new HashMap<>();
	private volatile String[] labels;

	public MobileVitV2Engine(Context context) {
		this.appContext = context.getApplicationContext();
	}

	public boolean isAvailable() {
		for (String modelPath : ASSET_MODELS) {
			try (InputStream ignored = appContext.getAssets().open(modelPath)) {
				// OK
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}

	public MultiClassificationResult classify(Uri imageUri) throws IOException {
		if (!isAvailable()) {
			throw new IOException("One or more model assets not found.");
		}
		Bitmap bitmap = decodeForModel(imageUri);
		if (bitmap == null) {
			throw new IOException("Could not decode image");
		}
		try {
			// Apply pre-processing: Squash resize to 256x256 + Channel mean normalization
			Bitmap preprocessed = applyPreProcessing(bitmap);
			if (preprocessed != bitmap) {
				bitmap.recycle();
			}
			float[] inputChw = bitmapToNchwFloats(preprocessed);
			preprocessed.recycle();

			Map<String, ClassificationResult> results = new LinkedHashMap<>();
			boolean anySuspicious = false;

			for (String modelPath : ASSET_MODELS) {
				ClassificationResult res = runSession(modelPath, inputChw);
				results.put(modelPath, res);
				
				// Assuming binary classification where index 1 is "suspicious" or single sigmoid output
				double suspiciousProb = (res.probs.length > 1) ? res.probs[1] : res.probs[0];
				if (suspiciousProb > 0.5) {
					anySuspicious = true;
				}
			}

			return new MultiClassificationResult(results, anySuspicious);
		} catch (OrtException e) {
			throw new IOException("ONNX inference failed", e);
		}
	}

	private Bitmap decodeForModel(Uri uri) throws IOException {
		int outW;
		int outH;
		try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
			if (is == null) {
				return null;
			}
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			BitmapFactory.decodeStream(is, null, bounds);
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
				return null;
			}
			outW = bounds.outWidth;
			outH = bounds.outHeight;
		}
		try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
			if (is == null) {
				return null;
			}
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inSampleSize = sampleSizeForMaxSide(outW, outH, CROP_SIZE * 2);
			return BitmapFactory.decodeStream(is, null, options);
		}
	}

	private static int sampleSizeForMaxSide(int width, int height, int maxDimension) {
		int maxSide = Math.max(width, height);
		int sample = 1;
		while (maxSide / sample > maxDimension) {
			sample *= 2;
		}
		return sample;
	}

	/**
	 * Pre-processing logic translated from assets/android_pre_processing.kt
	 * 1. Squash resize to 256x256
	 * 2. Calculate channel means
	 * 3. Scale by 128/mean, clip to [0,255], and round
	 */
	private static Bitmap applyPreProcessing(Bitmap inputBitmap) {
		// 1. Resize to 256x256 (Squash)
		Bitmap scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, CROP_SIZE, CROP_SIZE, true);
		int width = scaledBitmap.getWidth();
		int height = scaledBitmap.getHeight();
		int[] pixels = new int[width * height];
		scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

		// 2. Calculate channel means
		double sumR = 0, sumG = 0, sumB = 0;
		for (int pixel : pixels) {
			sumR += (pixel >> 16) & 0xFF;
			sumG += (pixel >> 8) & 0xFF;
			sumB += pixel & 0xFF;
		}
		double count = (double) width * height;
		double meanR = sumR / count;
		double meanG = sumG / count;
		double meanB = sumB / count;

		double scaleR = (meanR != 0) ? 128.0 / meanR : 1.0;
		double scaleG = (meanG != 0) ? 128.0 / meanG : 1.0;
		double scaleB = (meanB != 0) ? 128.0 / meanB : 1.0;

		// 3. Apply scaling, clipping, and rounding
		int[] outPixels = new int[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			int r = (int) Math.max(0, Math.min(255, Math.round(((pixels[i] >> 16) & 0xFF) * scaleR)));
			int g = (int) Math.max(0, Math.min(255, Math.round(((pixels[i] >> 8) & 0xFF) * scaleG)));
			int b = (int) Math.max(0, Math.min(255, Math.round((pixels[i] & 0xFF) * scaleB)));
			outPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}

		Bitmap outputBitmap = Bitmap.createBitmap(CROP_SIZE, CROP_SIZE, Bitmap.Config.ARGB_8888);
		outputBitmap.setPixels(outPixels, 0, width, 0, 0, width, height);

		if (scaledBitmap != inputBitmap) {
			scaledBitmap.recycle();
		}
		return outputBitmap;
	}

	private static float[] bitmapToNchwFloats(Bitmap bitmap) {
		int size = CROP_SIZE;
		int plane = size * size;
		float[] chw = new float[3 * plane];
		int[] px = new int[plane];
		bitmap.getPixels(px, 0, size, 0, 0, size, size);
		for (int i = 0; i < plane; i++) {
			int p = px[i];
			float r = ((p >> 16) & 0xff) / 255f;
			float g = ((p >> 8) & 0xff) / 255f;
			float b = (p & 0xff) / 255f;
			chw[i] = b;
			chw[plane + i] = g;
			chw[2 * plane + i] = r;
		}
		return chw;
	}

	private ClassificationResult runSession(String modelPath, float[] inputChw) throws OrtException, IOException {
		OrtSession ortSession = getOrCreateSession(modelPath);
		long[] shape = new long[] { 1, 3, CROP_SIZE, CROP_SIZE };
		ByteBuffer buffer = ByteBuffer.allocateDirect(inputChw.length * 4).order(ByteOrder.nativeOrder());
		FloatBuffer floatBuffer = buffer.asFloatBuffer();
		floatBuffer.put(inputChw);
		floatBuffer.rewind();

		OrtEnvironment env = OrtEnvironment.getEnvironment();
		try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape);
			 OrtSession.Result result = ortSession.run(Collections.singletonMap("input", inputTensor))) {

			OnnxValue logitsVal = result.get(0);
			Object val = logitsVal.getValue();
			float[] logits = flattenLogits(val);
			if (logits == null || logits.length == 0) {
				throw new OrtException(OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION, "Empty logits from model " + modelPath);
			}
			int argmax = argMax(logits);
			double[] probs = (logits.length > 1) ? softmax(logits) : new double[] { (double)logits[0] };
			double confidence = (probs.length > 1) ? probs[argmax] : probs[0];
			String label = labelForIndex(argmax);
			ScoredLabel[] top5 = (probs.length > 1) ? topKScoredLabels(probs, 5) : null;
			return new ClassificationResult(argmax, label, confidence, top5, probs);
		}
	}

	private ScoredLabel[] topKScoredLabels(double[] probs, int k) throws IOException {
		int n = Math.min(k, probs.length);
		Integer[] order = new Integer[probs.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Double.compare(probs[b], probs[a]));
		ScoredLabel[] out = new ScoredLabel[n];
		for (int i = 0; i < n; i++) {
			int idx = order[i];
			out[i] = new ScoredLabel(labelForIndex(idx), probs[idx]);
		}
		return out;
	}

	private OrtSession getOrCreateSession(String modelPath) throws IOException, OrtException {
		synchronized (sessionLock) {
			OrtSession session = sessions.get(modelPath);
			if (session != null) {
				return session;
			}
			String absolutePath = copyAssetToFilesDir(modelPath);
			OrtEnvironment env = OrtEnvironment.getEnvironment();
			OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
			opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
			session = env.createSession(absolutePath, opts);
			sessions.put(modelPath, session);
			trace(this, "MobileVitV2Engine :: ONNX session created for %s", modelPath);
			return session;
		}
	}

	private String labelForIndex(int index) throws IOException {
		String[] lbl = getLabels();
		if (index >= 0 && index < lbl.length) {
			return lbl[index];
		}
		return "class_" + index;
	}

	private String[] getLabels() throws IOException {
		if (labels != null) {
			return labels;
		}
		synchronized (sessionLock) {
			if (labels != null) {
				return labels;
			}
			try (InputStream is = appContext.getAssets().open(ASSET_LABELS);
				 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line);
				}
				try {
					JSONArray arr = new JSONArray(sb.toString());
					String[] out = new String[arr.length()];
					for (int i = 0; i < arr.length(); i++) {
						out[i] = arr.getString(i);
					}
					labels = out;
					return out;
				} catch (JSONException e) {
					throw new IOException("Invalid " + ASSET_LABELS, e);
				}
			}
		}
	}

	private String copyAssetToFilesDir(String assetPath) throws IOException {
		String fileName = new File(assetPath).getName();
		File outFile = new File(appContext.getFilesDir(), fileName);
		if (outFile.exists() && outFile.length() > 0) {
			copyOptionalExternalDataAsset(assetPath, outFile.getParentFile());
			return outFile.getAbsolutePath();
		}
		try (InputStream is = appContext.getAssets().open(assetPath);
			 FileOutputStream fos = new FileOutputStream(outFile)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) {
				fos.write(buf, 0, n);
			}
		}
		copyOptionalExternalDataAsset(assetPath, outFile.getParentFile());
		return outFile.getAbsolutePath();
	}

	private void copyOptionalExternalDataAsset(String assetPath, File outputDir) throws IOException {
		String dataAssetPath = assetPath + ".data";
		File dataOutFile = new File(outputDir, new File(dataAssetPath).getName());
		try (InputStream is = appContext.getAssets().open(dataAssetPath);
			 FileOutputStream fos = new FileOutputStream(dataOutFile)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) {
				fos.write(buf, 0, n);
			}
			trace(this, "MobileVitV2Engine :: Copied external ONNX data file: %s", dataOutFile.getAbsolutePath());
		} catch (IOException ignored) {
			// Most models are single-file ONNX and do not use external data.
		}
	}

	private static float[] flattenLogits(Object val) {
		if (val instanceof float[][]) {
			return ((float[][]) val)[0];
		}
		if (val instanceof float[]) {
			return (float[]) val;
		}
		return null;
	}

	private static int argMax(float[] logits) {
		if (logits.length == 0) return -1;
		if (logits.length == 1) return 0;
		int best = 0;
		float max = logits[0];
		for (int i = 1; i < logits.length; i++) {
			if (logits[i] > max) {
				max = logits[i];
				best = i;
			}
		}
		return best;
	}

	private static double[] softmax(float[] logits) {
		double max = Double.NEGATIVE_INFINITY;
		for (float v : logits) {
			max = Math.max(max, v);
		}
		double sum = 0;
		double[] exp = new double[logits.length];
		for (int i = 0; i < logits.length; i++) {
			exp[i] = Math.exp(logits[i] - max);
			sum += exp[i];
		}
		for (int i = 0; i < logits.length; i++) {
			exp[i] /= sum;
		}
		return exp;
	}

	public static final class ScoredLabel {
		public final String label;
		public final double score;

		ScoredLabel(String label, double score) {
			this.label = label;
			this.score = score;
		}
	}

	public static final class ClassificationResult {
		public final int classIndex;
		public final String label;
		public final double confidence;
		public final ScoredLabel[] top5;
		public final double[] probs;

		ClassificationResult(int classIndex, String label, double confidence, ScoredLabel[] top5, double[] probs) {
			this.classIndex = classIndex;
			this.label = label;
			this.confidence = confidence;
			this.top5 = top5;
			this.probs = probs;
		}
	}

	public static final class MultiClassificationResult {
		public final Map<String, ClassificationResult> modelResults;
		public final boolean isSuspicious;

		MultiClassificationResult(Map<String, ClassificationResult> modelResults, boolean isSuspicious) {
			this.modelResults = modelResults;
			this.isSuspicious = isSuspicious;
		}
	}

	/**
	 * Releases native sessions (e.g. when activity is destroyed).
	 */
	public void close() {
		synchronized (sessionLock) {
			for (OrtSession s : sessions.values()) {
				try {
					s.close();
				} catch (OrtException e) {
					warn(this, "MobileVitV2Engine :: session close failed: %s", e.getMessage());
				}
			}
			sessions.clear();
		}
	}
}
