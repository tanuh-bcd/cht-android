package org.medicmobile.webapp.mobile;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.app.Activity.RESULT_OK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.MediaStore.ACTION_IMAGE_CAPTURE;
import static android.provider.MediaStore.ACTION_VIDEO_CAPTURE;
import static android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION;
import static android.provider.MediaStore.EXTRA_OUTPUT;
import static org.medicmobile.webapp.mobile.EmbeddedBrowserActivity.RequestCode;
import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

public class FilePickerHandler {
	private static final int MAX_IMAGE_DIMENSION = 1280;
	private static final int TARGET_IMAGE_BYTES = 350 * 1024;
	private static final int INITIAL_JPEG_QUALITY = 78;
	private static final int MIN_JPEG_QUALITY = 40;
	private static final int HARD_MAX_IMAGE_BYTES = 250 * 1024;
	private static final int MIN_IMAGE_DIMENSION = 640;

	private final Activity context;
	private ValueCallback<Uri[]> filePickerCallback;
	private String[] mimeTypes;
	private File tempFile;
	/** Invoked after the WebView receives the compressed image URI (same thread as the picker callback). */
	private Consumer<Uri> onCompressedImageReady;

	public FilePickerHandler(Activity context) {
		this.context = context;
	}

	/**
	 * Optional hook to run offline processing (e.g. MobileViTV2) on each picked image after upload bytes are handed to the WebView.
	 */
	void setOnCompressedImageReady(Consumer<Uri> onCompressedImageReady) {
		this.onCompressedImageReady = onCompressedImageReady;
	}

	void setFilePickerCallback(ValueCallback<Uri[]> filePickerCallback) {
		this.filePickerCallback = filePickerCallback;
	}

	void setTempFile(File tempFile) {
		this.tempFile = tempFile;
	}

	void openPicker(FileChooserParams fileChooserParams, ValueCallback<Uri[]> filePickerCallback) {
		setFilePickerCallback(filePickerCallback);
		setTempFile(null);
		setMimeTypes(cleanMimeTypes(fileChooserParams));

		if (checkPermissions()) {
			startFileCaptureActivity();
		}
	}

	void processResult(int resultCode, Intent intent) {
		if (resultCode != RESULT_OK) {
			String message = "FilePickerHandler :: Bad result code: %s. " +
				"Either the user didn't select a file or there's an error during the operation.";
			warn(this, message, resultCode);
			sendDataToWebapp(null);
			return;
		}

		sendDataToWebapp(intent);
	}

	void resumeProcess(int resultCode) {
		if (resultCode == RESULT_OK) {
			startFileCaptureActivity();
			return;
		}

		// Giving control back to WebView without files.
		sendDataToWebapp(null);
	}

//> PRIVATE
	private void setMimeTypes(String[] mimeTypes) {
		this.mimeTypes = mimeTypes;
	}

	private boolean checkPermissions() {
		if (ContextCompat.checkSelfPermission(this.context, READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
			return true;
		}

		trace(this, "FilePickerHandler :: Requesting permissions.");
		Intent intent = new Intent(this.context, RequestStoragePermissionActivity.class);
		intent.putExtra(
			RequestStoragePermissionActivity.TRIGGER_CLASS,
			FilePickerHandler.class.getName()
		);
		this.context.startActivityForResult(intent, RequestCode.ACCESS_STORAGE_PERMISSION.getCode());
		return false;
	}

	/**
	 * Executes the file picker callback.
	 * The callback will give control back to WebView that automatically fetches the file by using a
	 * provided URI, finally it assigns the bytes to the form's input. Even if there's not URI (ie null),
	 * it needs to give control back to WebView, otherwise when clicking again on the form's input
	 * it won't open the file picker.
	 *
	 * @param intent Intent from activity's response.
	 */
	private void sendDataToWebapp(Intent intent) {
		if (this.filePickerCallback == null) {
			warn(this, "FilePickerHandler :: Cannot send data back to webapp, filePickerCallback is null.");
			return;
		}

		Uri[] uris = getSelectedFileUri(intent)
			.map(uri -> new Uri[]{uri})
			.orElse(null);
		trace(this, "FilePickerHandler :: Sending data back to webapp, URI: %s", Arrays.toString(uris));
		this.filePickerCallback.onReceiveValue(uris);
		this.filePickerCallback = null;

		if (uris != null && uris.length > 0 && this.onCompressedImageReady != null && isImageUri(uris[0])) {
			this.onCompressedImageReady.accept(uris[0]);
		}
	}

	private Optional<Uri> getSelectedFileUri(Intent intent) {
		Optional<Uri> uri = intent == null ? Optional.empty() : Optional.ofNullable(intent.getData());

		if (!uri.isPresent() && this.tempFile != null) {
			if (this.tempFile.length() > 0) {
				uri = Optional.ofNullable(getTempFileUri(this.tempFile));
			}
			setTempFile(null);
		}

		if (!uri.isPresent()) {
			return Optional.empty();
		}
		return Optional.ofNullable(getCompressedImageUri(uri.get()));
	}

	private Uri getCompressedImageUri(Uri uri) {
		if (!isImageUri(uri)) {
			return uri;
		}

		try {
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			try (InputStream inputStream = this.context.getContentResolver().openInputStream(uri)) {
				if (inputStream == null) {
					return uri;
				}
				BitmapFactory.decodeStream(inputStream, null, bounds);
			}

			if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
				return uri;
			}

			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight);
			Bitmap bitmap;
			try (InputStream inputStream = this.context.getContentResolver().openInputStream(uri)) {
				if (inputStream == null) {
					return uri;
				}
				bitmap = BitmapFactory.decodeStream(inputStream, null, options);
			}

			if (bitmap == null) {
				return uri;
			}

			Bitmap resizedBitmap = resizeBitmap(bitmap);
			if (resizedBitmap != bitmap) {
				bitmap.recycle();
			}

			File storageDir = this.context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
			if (storageDir == null) {
				resizedBitmap.recycle();
				return uri;
			}

			File outputFile = File.createTempFile("medic-compressed-" + System.currentTimeMillis(), ".jpg", storageDir);
			byte[] compressed = compressBitmap(resizedBitmap);
			try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
				outputStream.write(compressed);
				outputStream.flush();
			}
			resizedBitmap.recycle();
			trace(this, "FilePickerHandler :: Compressed image size: %s bytes", compressed.length);
			return getTempFileUri(outputFile);

		} catch (Exception exception) {
			warn(this, "FilePickerHandler :: Failed to compress image. Using original URI. Error: %s", exception);
			return uri;
		}
	}

	private boolean isImageUri(Uri uri) {
		ContentResolver resolver = this.context.getContentResolver();
		if (resolver != null) {
			String mimeType = resolver.getType(uri);
			if (mimeType != null) {
				return mimeType.startsWith("image/");
			}
		}
		String path = uri.getPath();
		return path != null && (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png"));
	}

	private int computeSampleSize(int width, int height) {
		int sampleSize = 1;
		while ((width / sampleSize) > MAX_IMAGE_DIMENSION || (height / sampleSize) > MAX_IMAGE_DIMENSION) {
			sampleSize *= 2;
		}
		return sampleSize;
	}

	private Bitmap resizeBitmap(Bitmap bitmap) {
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		int largestDimension = Math.max(width, height);
		if (largestDimension <= MAX_IMAGE_DIMENSION) {
			return bitmap;
		}

		float ratio = (float) MAX_IMAGE_DIMENSION / largestDimension;
		int targetWidth = Math.max(1, Math.round(width * ratio));
		int targetHeight = Math.max(1, Math.round(height * ratio));
		return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
	}

	private byte[] compressBitmap(Bitmap bitmap) {
		Bitmap currentBitmap = bitmap;
		byte[] bytes = compressBitmapAtQuality(currentBitmap);
		while (bytes.length > HARD_MAX_IMAGE_BYTES && canDownscale(currentBitmap)) {
			int targetWidth = Math.max(MIN_IMAGE_DIMENSION, Math.round(currentBitmap.getWidth() * 0.85f));
			int targetHeight = Math.max(MIN_IMAGE_DIMENSION, Math.round(currentBitmap.getHeight() * 0.85f));
			Bitmap downscaled = Bitmap.createScaledBitmap(currentBitmap, targetWidth, targetHeight, true);
			if (downscaled == currentBitmap) {
				break;
			}
			if (currentBitmap != bitmap) {
				currentBitmap.recycle();
			}
			currentBitmap = downscaled;
			bytes = compressBitmapAtQuality(currentBitmap);
		}

		if (currentBitmap != bitmap) {
			currentBitmap.recycle();
		}
		return bytes;
	}

	private byte[] compressBitmapAtQuality(Bitmap bitmap) {
		int quality = INITIAL_JPEG_QUALITY;
		byte[] bytes = new byte[]{};
		do {
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
				bytes = output.toByteArray();
			} catch (Exception exception) {
				warn(this, "FilePickerHandler :: Error compressing bitmap at quality %s", quality);
				break;
			}
			quality -= 8;
		} while (bytes.length > TARGET_IMAGE_BYTES && quality >= MIN_JPEG_QUALITY);
		return bytes;
	}

	private boolean canDownscale(Bitmap bitmap) {
		return bitmap.getWidth() > MIN_IMAGE_DIMENSION || bitmap.getHeight() > MIN_IMAGE_DIMENSION;
	}

	private void startFileCaptureActivity() {
		trace(this, "FilePickerHandler :: Accepted MIME types: %s", Arrays.toString(this.mimeTypes));

		Intent pickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
		pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
		pickerIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
		Intent captureIntent = null;

		if (this.mimeTypes.length == 1) {
			String mimeType = this.mimeTypes[0];
			pickerIntent.setType(mimeType);
			captureIntent = getCaptureIntent(mimeType);
		} else {
			pickerIntent.setType("*/*");
			pickerIntent.putExtra(Intent.EXTRA_MIME_TYPES, this.mimeTypes);
		}

		Intent chooserIntent = Intent.createChooser(pickerIntent, this.context.getString(R.string.promptChooseFile));
		// Ensure that there's an activity to handle the intent for capturing media.
		if (captureIntent != null && captureIntent.resolveActivity(this.context.getPackageManager()) != null) {
			chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ captureIntent });
		}

		trace(this, "FilePickerHandler :: Starting activity with intents: chooser=%s, picker=%s, capture=%s",
			chooserIntent, pickerIntent, captureIntent);

		this.context.startActivityForResult(chooserIntent, RequestCode.FILE_PICKER_ACTIVITY.getCode());
	}

	private Intent getCaptureIntent(String mimeType) {
		if (mimeType.contains("video/")) {
			return new Intent(ACTION_VIDEO_CAPTURE);
		}

		if (mimeType.contains("audio/")) {
			return new Intent(RECORD_SOUND_ACTION);
		}

		if (mimeType.contains("image/")) {
			try {
				createTempImageFile();
				Uri uri = getTempFileUri(this.tempFile);
				Intent imageIntent = new Intent(ACTION_IMAGE_CAPTURE);
				imageIntent.putExtra(EXTRA_OUTPUT, uri);
				return imageIntent;
			} catch (Exception ex) {
				warn(this, "FilePickerHandler :: Cannot create temporary file for image capture: %s", ex);
			}
		}

		trace(this, "FilePickerHandler :: No capture intent for MIME type: %s", mimeType);
		return null;
	}

	private void createTempImageFile() throws Exception {
		String imageFileName = "medic-" + System.currentTimeMillis();
		File storageDir = this.context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
		File file = File.createTempFile(imageFileName, ".jpg", storageDir);
		file.deleteOnExit();
		setTempFile(file);
	}

	private Uri getTempFileUri(File file) {
		return FileProvider.getUriForFile(this.context, this.context.getPackageName() + ".fileprovider", file);
	}

	/**
	 * Removes empty strings from the array of AcceptTypes that is returned by FileChooserParams.
	 * That happens when defining [mediatype=""] in an Enketo form, example:
	 * <upload mediatype="" ref="/enketo_widgets/media_widgets/any_file"/>
	 *
	 * @param fileChooserParams WebView's FileChooserParams
	 * @return
	 */
	private String[] cleanMimeTypes(FileChooserParams fileChooserParams) {
		String[] types = Optional
			.ofNullable(fileChooserParams.getAcceptTypes())
			.orElse(new String[]{});

		return Arrays
			.stream(types)
			.filter(type -> !type.isEmpty())
			.toArray(String[]::new);
	}
}
