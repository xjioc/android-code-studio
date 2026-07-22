package com.tom.rv2ide.services.builder;

import com.tom.rv2ide.services.builder.IAetherBuildCallback;

interface IAetherBuildService {
    boolean isToolingServerStarted();
    boolean isBuildInProgress();
    String getSdkPath();
    String getJavaHome();

    void initializeProject(String projectDir, IAetherBuildCallback callback);
    void executeTasks(in List<String> tasks, IAetherBuildCallback callback);
    void executeShell(String command, IAetherBuildCallback callback);
    void cancelBuild();

    String getProjectTree(String rootDir);
    String getModulesJson();
    String getVariantsJson(String modulePath);
    String getAvailableTasksJson(String modulePath);

    void registerCallback(IAetherBuildCallback callback);
    void unregisterCallback(IAetherBuildCallback callback);
}
