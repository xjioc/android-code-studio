package com.tom.rv2ide.services.builder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import com.google.gson.Gson
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.models.params.StringParameter
import com.tom.rv2ide.tooling.events.ProgressEvent
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class AetherBuildAidlService : Service() {

  private val callbacks = RemoteCallbackList<IAetherBuildCallback>()
  private val gson = Gson()
  private val serviceScope =
      CoroutineScope(Dispatchers.IO + CoroutineName("AetherBuildAidlService"))
  private var shellJob: Job? = null
  private var shellProcess: Process? = null

  private val buildService: GradleBuildService?
    get() = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService

  private val projectProxy: IProject?
    get() = Lookup.getDefault().lookup(BuildService.KEY_PROJECT_PROXY) as? IProject

  private val binder =
      object : IAetherBuildService.Stub() {

        override fun isToolingServerStarted(): Boolean {
          return buildService?.isToolingServerStarted() ?: false
        }

        override fun isBuildInProgress(): Boolean {
          return buildService?.isBuildInProgress ?: false
        }

        override fun getSdkPath(): String {
          return Environment.ANDROID_HOME?.absolutePath ?: ""
        }

        override fun getJavaHome(): String {
          return Environment.JAVA_HOME?.absolutePath ?: ""
        }

        override fun initializeProject(projectDir: String, callback: IAetherBuildCallback?) {
          val service = buildService
          if (service == null) {
            callback?.onProjectInitialized(false, "Build service not available")
            return
          }

          ensureToolingServerStarted(service)

          serviceScope.launch {
            try {
              val params = InitializeProjectParams(directory = projectDir)
              val result = service.initializeProject(params).get()
              val message =
                  if (result.isSuccessful) "Project initialized successfully"
                  else result.failure?.name ?: "Initialization failed"
              callback?.onProjectInitialized(result.isSuccessful, message)
            } catch (e: Exception) {
              log.error("Failed to initialize project: {}", projectDir, e)
              callback?.onProjectInitialized(false, e.message ?: "Unknown error")
            }
          }
        }

        override fun executeTasks(tasks: MutableList<String>, callback: IAetherBuildCallback?) {
          val service = buildService
          if (service == null) {
            callback?.onBuildFailed(tasks, "Build service not available")
            return
          }

          if (!service.isToolingServerStarted()) {
            callback?.onBuildFailed(tasks, "Tooling server not started")
            return
          }

          if (callback != null) {
            callbacks.register(callback)
          }

          installEventListener()

          serviceScope.launch {
            try {
              val result = service.executeTasks(*tasks.toTypedArray()).get()
              if (result.isSuccessful) {
                broadcastBuildSuccessful(tasks)
              } else {
                broadcastBuildFailed(tasks, result.failure?.name ?: "Build failed")
              }
            } catch (e: Exception) {
              log.error("Failed to execute tasks: {}", tasks, e)
              broadcastBuildFailed(tasks, e.message ?: "Unknown error")
            } finally {
              if (callback != null) {
                callbacks.unregister(callback)
              }
            }
          }
        }

        override fun executeShell(command: String, callback: IAetherBuildCallback?) {
          shellJob?.cancel()
          shellProcess?.destroyForcibly()

          shellJob =
              serviceScope.launch {
                try {
                  val envs = HashMap<String, String>()
                  Environment.putEnvironment(envs, false)

                  val process =
                      executeProcessAsync {
                        this.command =
                            listOf(Environment.BASH_SHELL.absolutePath, "-c", command)
                        this.environment = envs
                        this.workingDirectory = Environment.HOME
                        this.redirectErrorStream = true
                      }

                  shellProcess = process

                  callback?.onBuildStarted(gson.toJson(mapOf("command" to command)))

                  process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line -> callback?.onOutput(line) }
                  }

                  val exitCode = process.waitFor()
                  if (exitCode == 0) {
                    callback?.onBuildSuccessful(listOf(command))
                  } else {
                    callback?.onBuildFailed(listOf(command), "Exit code: $exitCode")
                  }
                } catch (e: Exception) {
                  log.error("Failed to execute shell command: {}", command, e)
                  callback?.onBuildFailed(listOf(command), e.message ?: "Unknown error")
                } finally {
                  shellProcess = null
                }
              }
        }

        override fun cancelBuild() {
          shellJob?.cancel()
          shellProcess?.destroyForcibly()
          shellProcess = null

          buildService?.cancelCurrentBuild()
        }

        override fun getProjectTree(rootDir: String): String {
          return try {
            val root = File(rootDir)
            if (!root.exists() || !root.isDirectory) {
              return gson.toJson(mapOf("error" to "Directory not found: $rootDir"))
            }
            val tree = buildProjectTree(root, 0)
            val json = gson.toJson(tree)
            truncateIfNeeded(json)
          } catch (e: Exception) {
            log.error("Failed to get project tree for: {}", rootDir, e)
            gson.toJson(mapOf("error" to (e.message ?: "Unknown error")))
          }
        }

        override fun getModulesJson(): String {
          val proxy = projectProxy
          if (proxy == null) {
            return gson.toJson(mapOf("error" to "Project not initialized"))
          }

          return try {
            val projects = proxy.getProjects().get()
            val modules =
                projects.map { p ->
                  mapOf(
                      "name" to (p.name ?: IProject.PROJECT_UNKNOWN),
                      "path" to p.projectPath,
                      "projectDir" to p.projectDir.absolutePath,
                      "buildDir" to p.buildDir.absolutePath,
                  )
                }
            truncateIfNeeded(gson.toJson(modules))
          } catch (e: Exception) {
            log.error("Failed to get modules", e)
            gson.toJson(mapOf("error" to (e.message ?: "Unknown error")))
          }
        }

        override fun getVariantsJson(modulePath: String): String {
          val proxy = projectProxy
          if (proxy == null) {
            return gson.toJson(mapOf("error" to "Project not initialized"))
          }

          return try {
            proxy.selectProject(StringParameter(modulePath)).get()
            val variants = proxy.asAndroidProject().getVariants().get()
            val result =
                variants.map { v ->
                  mapOf("name" to v.name)
                }
            truncateIfNeeded(gson.toJson(result))
          } catch (e: Exception) {
            log.error("Failed to get variants for: {}", modulePath, e)
            gson.toJson(mapOf("error" to (e.message ?: "Unknown error")))
          }
        }

        override fun getAvailableTasksJson(modulePath: String): String {
          val proxy = projectProxy
          if (proxy == null) {
            return gson.toJson(mapOf("error" to "Project not initialized"))
          }

          return try {
            proxy.selectProject(StringParameter(modulePath)).get()
            val tasks = proxy.asGradleProject().getTasks().get()
            val result =
                tasks.map { t ->
                  mapOf(
                      "name" to t.name,
                      "path" to t.path,
                      "description" to (t.description ?: ""),
                      "group" to (t.group ?: ""),
                  )
                }
            truncateIfNeeded(gson.toJson(result))
          } catch (e: Exception) {
            log.error("Failed to get tasks for: {}", modulePath, e)
            gson.toJson(mapOf("error" to (e.message ?: "Unknown error")))
          }
        }

        override fun registerCallback(callback: IAetherBuildCallback?) {
          if (callback != null) {
            callbacks.register(callback)
          }
        }

        override fun unregisterCallback(callback: IAetherBuildCallback?) {
          if (callback != null) {
            callbacks.unregister(callback)
          }
        }
      }

  override fun onBind(intent: Intent): IBinder {
    return binder
  }

  override fun onDestroy() {
    callbacks.kill()
    shellJob?.cancel()
    shellProcess?.destroyForcibly()
    super.onDestroy()
  }

  private fun ensureToolingServerStarted(service: GradleBuildService) {
    if (!service.isToolingServerStarted()) {
      service.startToolingServer { pid ->
        log.info("Tooling server started with pid: {}", pid)
      }
    }
  }

  private fun installEventListener() {
    val service = buildService ?: return
    service.setEventListener(
        object : GradleBuildService.EventListener {
          override fun prepareBuild(buildInfo: BuildInfo) {
            broadcastBuildStarted(buildInfo)
          }

          override fun onBuildSuccessful(tasks: List<String?>) {
            broadcastBuildSuccessful(tasks.filterNotNull())
          }

          override fun onProgressEvent(event: ProgressEvent) {
            broadcastProgressEvent(event)
          }

          override fun onBuildFailed(tasks: List<String?>) {
            broadcastBuildFailed(tasks.filterNotNull(), "Build failed")
          }

          override fun onOutput(line: String?) {
            if (line != null) {
              broadcastOutput(line)
            }
          }
        }
    )
  }

  private fun broadcastBuildStarted(buildInfo: BuildInfo) {
    val json = gson.toJson(mapOf("tasks" to buildInfo.tasks))
    val n = callbacks.beginBroadcast()
    try {
      for (i in 0 until n) {
        callbacks.getBroadcastItem(i).onBuildStarted(json)
      }
    } finally {
      callbacks.finishBroadcast()
    }
  }

  private fun broadcastBuildSuccessful(tasks: List<String>) {
    val n = callbacks.beginBroadcast()
    try {
      for (i in 0 until n) {
        callbacks.getBroadcastItem(i).onBuildSuccessful(tasks)
      }
    } finally {
      callbacks.finishBroadcast()
    }
  }

  private fun broadcastBuildFailed(tasks: List<String>, errorMessage: String) {
    val n = callbacks.beginBroadcast()
    try {
      for (i in 0 until n) {
        callbacks.getBroadcastItem(i).onBuildFailed(tasks, errorMessage)
      }
    } finally {
      callbacks.finishBroadcast()
    }
  }

  private fun broadcastOutput(line: String) {
    val n = callbacks.beginBroadcast()
    try {
      for (i in 0 until n) {
        callbacks.getBroadcastItem(i).onOutput(line)
      }
    } finally {
      callbacks.finishBroadcast()
    }
  }

  private fun broadcastProgressEvent(event: ProgressEvent) {
    val json =
        try {
          gson.toJson(
              mapOf(
                  "displayName" to event.displayName,
                  "eventTime" to event.eventTime,
              )
          )
        } catch (e: Exception) {
          return
        }

    val n = callbacks.beginBroadcast()
    try {
      for (i in 0 until n) {
        callbacks.getBroadcastItem(i).onProgressEvent(json)
      }
    } finally {
      callbacks.finishBroadcast()
    }
  }

  private fun buildProjectTree(dir: File, depth: Int): Map<String, Any?> {
    val node = mutableMapOf<String, Any?>("name" to dir.name, "path" to dir.absolutePath)

    if (dir.isDirectory && depth < MAX_TREE_DEPTH) {
      val children =
          dir
              .listFiles()
              ?.filter { !it.name.startsWith(".") && it.name != "build" }
              ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
              ?.take(MAX_CHILDREN_PER_DIR)
              ?.map { buildProjectTree(it, depth + 1) }
              ?: emptyList()
      node["children"] = children
    }

    return node
  }

  private fun truncateIfNeeded(json: String): String {
    return if (json.length > MAX_RESPONSE_SIZE) {
      json.substring(0, MAX_RESPONSE_SIZE) + "...[truncated]"
    } else {
      json
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(AetherBuildAidlService::class.java)
    private const val MAX_TREE_DEPTH = 5
    private const val MAX_CHILDREN_PER_DIR = 100
    private const val MAX_RESPONSE_SIZE = 256 * 1024
  }
}
