package com.litertime.android.ipc;

import com.litertime.android.ipc.IInferenceCallback;

interface IInferenceService {
    void registerCallback(IInferenceCallback callback);
    void unregisterCallback(IInferenceCallback callback);
    /** forceGpu reloads/unloads the LLM with the GPU backend even if the setting is off. */
    void preloadModel(String engineType, String modelId, boolean forceGpu);
    /** Unloads just one engine ("ASR" or "LLM"); the other stays resident. */
    void unloadEngine(String engineType);
    /** Unloads every resident engine and stops the foreground service. */
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