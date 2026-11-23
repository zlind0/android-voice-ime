package com.litertime.android.ipc;

interface IInferenceCallback {
    oneway void onStatusChanged(String status, String loadedEngineType, String loadedModelId);
    oneway void onToken(String requestId, String delta);
    oneway void onComplete(String requestId);
    oneway void onError(String requestId, String errorMessage);
}
