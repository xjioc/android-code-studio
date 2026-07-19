/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.javac.services.fs

import com.tom.rv2ide.utils.VMUtils
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Singleton that caches file system information (canonical paths, attributes, jar classpaths).
 *
 * Replaces the original implementation that extended [openjdk.tools.javac.file.CacheFSInfo],
 * which is unavailable at runtime on some Android devices due to classloader restrictions
 * on the `openjdk` package.
 */
object CacheFSInfoSingleton {

  const val TEST_PROP_ENABLED_ON_JVM = "ide.testing.javac.fsCache.isEnabledOnJVM"
  private val log = LoggerFactory.getLogger(CacheFSInfoSingleton::class.java)

  private val canonicalPathCache = ConcurrentHashMap<Path, Path>()
  private val attributeCache = ConcurrentHashMap<Path, BasicFileAttributes?>()
  private val jarClassPathCache = ConcurrentHashMap<Path, List<Path>>()

  /** Caches information about the given [Path]. */
  @JvmOverloads
  fun cache(file: Path, cacheJarClasspath: Boolean = true) {
    if (System.getProperty(TEST_PROP_ENABLED_ON_JVM, null) != "true") {
      if (VMUtils.isJvm()) {
        return
      }
    }

    try {
      getCanonicalFile(file)
      getAttributes(file)
      if (cacheJarClasspath) {
        getJarClassPath(file)
      }
    } catch (err: Throwable) {
      log.warn("Failed to cache jar file: {}", file, err)
    }
  }

  /** Returns a cached canonical path for [file]. */
  fun getCanonicalFile(file: Path): Path =
    canonicalPathCache.computeIfAbsent(file) {
      try {
        it.toRealPath()
      } catch (_: IOException) {
        it.toAbsolutePath().normalize()
      }
    }

  /** Returns cached [BasicFileAttributes] for [file]. */
  fun getAttributes(file: Path): BasicFileAttributes? =
    attributeCache.computeIfAbsent(file) {
      try {
        java.nio.file.Files.readAttributes(it, BasicFileAttributes::class.java)
      } catch (_: IOException) {
        null
      }
    }

  /** Returns a cached list of classpath entries from the jar at [file]. */
  fun getJarClassPath(file: Path): List<Path> =
    jarClassPathCache.computeIfAbsent(file) {
      try {
        JarFile(file.toFile()).use { jar ->
          jar.manifest?.mainAttributes?.getValue("Class-Path")
            ?.split(" ")
            ?.filter { it.isNotBlank() }
            ?.map { file.resolveSibling(it) }
            ?: emptyList()
        }
      } catch (_: IOException) {
        emptyList()
      }
    }

  fun clearCache() {
    canonicalPathCache.clear()
    attributeCache.clear()
    jarClassPathCache.clear()
  }
}