package com.tom.rv2ide.services.builder;

oneway interface IAetherBuildCallback {
    void onBuildStarted(String buildInfoJson);
    void onBuildSuccessful(in List<String> tasks);
    void onBuildFailed(in List<String> tasks, String errorMessage);
    void onOutput(String line);
    void onProgressEvent(String eventJson);
    void onProjectInitialized(boolean success, String message);
}
