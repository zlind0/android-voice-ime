package com.litertime.android.ipc;

interface IInferenceCallback {
    /**
     * stateJson carries the whole residency snapshot, e.g.
     * {"status":"...","asr":"whisper-small","llm":"gemma4-e4b","llmGpu":true,"pid":12345}
     */
    oneway void onStatusChanged(String stateJson);
    oneway void onToken(String requestId, String delta);
    oneway void onComplete(String requestId);
    oneway void onError(String requestId, String errorMessage);
}