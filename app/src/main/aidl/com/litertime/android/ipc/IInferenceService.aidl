package com.litertime.android.ipc;

import com.litertime.android.ipc.IInferenceCallback;

interface IInferenceService {
    void registerCallback(IInferenceCallback callback);
    void unregisterCallback(IInferenceCallback callback);
    void preloadModel(String engineType, String modelId);
    void unloadModel();
    String getLoadedStateJson();
    void runRequest(
        String requestId,
        String backendType,
        String mode,
        String modelId,
        String audioFilePath,
        String imageFilePath,
        String extraJson
    );
    void cancelRequest(String requestId);
}
