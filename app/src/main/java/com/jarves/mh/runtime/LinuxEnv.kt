package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * The Ubuntu 20.04 arm64 userspace that PRoot runs, plus the few things bb needs in it.
 * Kept from Mobile Harness: the PRoot launch, the verified Core bundle, the archive extraction.
 */
class LinuxEnv(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val workspace = File(context.filesDir, "workspace")
    private val proot get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val toolsMarker = File(rootfs, ".bbhost-tools-version")
    private val bbMarker = File(rootfs, ".bbhost-bb-installed")

    fun isBaseReady(): Boolean =
        ensureRootfsCompatibilityLinks() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/node").exists()

    fun isBbInstalled(): Boolean = bbMarker.exists() && File(rootfs, "root/.bb-npm/bin/bb-app").exists()

    /** Downloads (if needed) and unpacks the Core Ubuntu bundle. Node.js 24 ships inside it. */
    fun ensureBase(log: (String) -> Unit) {
        require(supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            "This device is not a native arm64 phone. The Linux environment needs arm64-v8a hardware."
        }
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }
        val staging = File(runtimeDir, "ubuntu.installing")
        val backup = File(runtimeDir, "ubuntu.repair-backup")
        RootfsRepair.reconcileInterruptedSwap(rootfs, staging, backup, ::isValidCoreRootfs)
        if (isBaseReady()) {
            writeResolver()
            log("Linux environment: already present")
            return
        }
        log("Linux environment: downloading Core bundle (${CORE_BYTES / 1_000_000} MB) from the Mobile Harness runtime release")
        val archive = File(downloads, CORE_FILE)
        var lastTenth = -1
        downloadVerified("$RUNTIME_BASE_URL/$CORE_FILE", archive, CORE_SHA256) { done, total ->
            val percent = if (total > 0) (done * 100 / total).toInt() else -1
            if (percent / 10 != lastTenth) log("  download $percent%")
            lastTenth = percent / 10
        }
        log("Linux environment: unpacking")
        staging.deleteRecursively()
        staging.mkdirs()
        extractZstdTar(archive, staging)
        RootfsRepair.activate(rootfs, staging, backup, ::isValidCoreRootfs)
        archive.delete()
        writeResolver()
        check(isBaseReady()) { "The unpacked Linux environment is incomplete (no bash or Node.js)" }
        log("Linux environment: ready")
    }

    /**
     * Git, Python and compilers. The Core bundle has Git and Node but no Python or compiler.
     * gcc-10/g++-10 alone install only /usr/bin/gcc-10 and g++-10 (checked against the focal arm64 Contents index),
     * so build-essential supplies gcc, g++, make and libc6-dev, and cc/c++/gcc/g++ are then pointed at version 10
     * because node-gyp and make look for those names.
     */
    fun ensureTools(log: (String) -> Unit, cancelled: () -> Boolean) {
        if (toolsMarker.readTextOrNull() == TOOLS_VERSION && File(rootfs, "usr/bin/g++-10").exists()) return
        log("Installing Python and build tools with apt (first run only)")
        val packages = "git ca-certificates python3 python3-distutils build-essential gcc-10 g++-10"
        val exit = runGuest(
            "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a; " +
                "apt-get -o DPkg::Lock::Timeout=120 update && " +
                "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packages && " +
                "ln -sf gcc-10 /usr/bin/gcc && ln -sf g++-10 /usr/bin/g++ && " +
                "ln -sf gcc-10 /usr/bin/cc && ln -sf g++-10 /usr/bin/c++ && " +
                "apt-get clean && rm -rf /var/lib/apt/lists/*",
            30 * 60_000L, log, cancelled,
        )
        check(exit == 0) { "apt could not install $packages (exit $exit)" }
        check(
            runGuest(
                "git --version && python3 --version && make --version | head -1 && " +
                    "for c in cc c++ gcc g++ gcc-10 g++-10; do \$c --version | head -1; done && " +
                    "echo 'int main(){return 0;}' > /tmp/t.c && cc /tmp/t.c -o /tmp/t && /tmp/t && " +
                    "printf '#include <string>\nint main(){std::string s;return (int)s.size();}\n' > /tmp/t.cc && c++ -std=c++20 /tmp/t.cc -o /tmp/t2 && /tmp/t2",
                120_000L, log, cancelled,
            ) == 0,
        ) { "Git or the compiler did not work in the Linux environment" }
        toolsMarker.writeText(TOOLS_VERSION)
    }

    /**
     * Installs bb-app into /root/.bb-npm inside the environment. Needs the network.
     * node-pty, fs-native-extensions and @parcel/watcher ship linux-arm64 prebuilds that fit glibc 2.31.
     * better-sqlite3's prebuild needs glibc 2.34 and GLIBCXX 3.4.29, which Ubuntu 20.04 lacks, so it is compiled here.
     */
    fun ensureBb(log: (String) -> Unit, cancelled: () -> Boolean) {
        if (isBbInstalled()) return
        log("Installing bb-app from npm (first run only; compiles SQLite, can take 10 minutes or more)")
        val env = "export npm_config_prefix=/root/.bb-npm npm_config_nodedir=/usr/local/lib/nodejs CC=gcc-10 CXX=g++-10; "
        var exit = runGuest(
            env + "node --version; npm --version; " +
                "npm install -g bb-app@latest --allow-scripts=better-sqlite3,node-pty,@parcel/watcher",
            60 * 60_000L, log, cancelled,
        )
        check(exit == 0) { "npm could not install bb-app (exit $exit). See the log above." }
        exit = runGuest(
            env + "found=0; for d in \$(find /root/.bb-npm/lib/node_modules -type d -path '*/node_modules/better-sqlite3'); do " +
                "found=1; " +
                "if (cd \$d && node -e \"new (require('.'))(':memory:').close()\"); then echo \"better-sqlite3 loads: \$d\"; continue; fi; " +
                "echo \"better-sqlite3 prebuilt binary does not load here; compiling from source: \$d\"; " +
                "rm -f \$d/prebuilds/linux-arm64.node; " +
                "(cd \$d && node /usr/local/lib/nodejs/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js rebuild --release --force_build=1) || exit 1; " +
                "(cd \$d && node -e \"new (require('.'))(':memory:').close()\") || exit 1; " +
                "done; test \$found = 1",
            90 * 60_000L, log, cancelled,
        )
        check(exit == 0) { "better-sqlite3 could not be made to work (exit $exit). See the log above." }
        check(runGuest("test -x /root/.bb-npm/bin/bb-app && /root/.bb-npm/bin/bb-app --help 2>&1 | head -5", 120_000L, log, cancelled) == 0) {
            "bb-app was installed but does not start"
        }
        bbMarker.writeText(BB_START)
    }

    /** Starts bb-app (server and host daemon) bound to loopback; the process runs until bb exits. */
    fun startBb(): Process = process(BB_START_COMMAND)

    /** Asks bb to stop itself cleanly. */
    fun stopBbGracefully(log: (String) -> Unit) {
        runGuest("/root/.bb-npm/bin/bb-app stop --data-dir $BB_DATA_DIR", 30_000L, log) { false }
    }

    /** Runs a command to completion and streams its output lines to [log]. */
    fun runGuest(command: String, timeoutMs: Long, log: (String) -> Unit, cancelled: () -> Boolean): Int {
        log("$ ${command.take(300)}")
        val running = process(command)
        val tail = LogTail((running as NativeSpawnProcess).outputFile, log)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (running.isAlive) {
                val stop = cancelled()
                if (stop || System.currentTimeMillis() > deadline) {
                    log(if (stop) "[cancelled]" else "[timed out]")
                    running.destroy()
                    break
                }
                if (!tail.pump()) Thread.sleep(100)
            }
            while (tail.pump()) Unit
            tail.flush()
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        log("[exit $exit]")
        return exit
    }

    fun process(command: String): Process {
        check(ensureRootfsCompatibilityLinks()) { "Linux environment has an invalid filesystem layout" }
        workspace.mkdirs()
        File(rootfs, "workspace").mkdirs()
        val args = buildList {
            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r"); add(rootfs.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b"); add(hostPath)
                }
            }
            add("-b"); add("${workspace.absolutePath}:/workspace")
            add("-w"); add("/workspace")
            addAll(listOf("/usr/bin/env", "bash", "-lc", command))
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val libDir = context.applicationInfo.nativeLibraryDir
        return NativeSpawnProcess.start(
            argv = args,
            environment = mapOf(
                "HOME" to "/root",
                "PATH" to "/root/.bb-npm/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG" to "C.UTF-8",
                "TERM" to "xterm-256color",
                "LD_LIBRARY_PATH" to libDir,
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_TMP_DIR" to prootTemp.absolutePath,
                "PROOT_LOADER" to File(libDir, "libprootloader.so").absolutePath,
                "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
                "CC" to "/usr/bin/gcc-10",
                "CXX" to "/usr/bin/g++-10",
            ),
            cwd = context.filesDir.absolutePath,
            outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        )
    }

    /** Follows a growing output file and hands complete lines to [log]. */
    class LogTail(private val file: File, private val log: (String) -> Unit) {
        private var offset = 0L
        private var pending = ""

        /** Returns true if it read anything. */
        fun pump(): Boolean {
            if (file.length() <= offset) return false
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val bytes = ByteArray((input.length() - offset).coerceAtMost(256 * 1024).toInt())
                input.readFully(bytes)
                offset += bytes.size
                pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
            }
            val parts = pending.split('\n')
            pending = parts.last()
            parts.dropLast(1).map(::clean).filter(String::isNotBlank).forEach(log)
            return true
        }

        fun flush() {
            clean(pending).takeIf(String::isNotBlank)?.let(log)
            pending = ""
        }

        private fun clean(raw: String) = raw.replace(ANSI_ESCAPE, "").filter { it == '\t' || it.code >= 32 }.take(600)
    }

    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf("bin" to "usr/bin", "lib" to "usr/lib", "sbin" to "usr/sbin")
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            android.util.Log.e("LinuxEnv", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            File(targetRootfs, ".pocket-rootfs-version").readTextOrNull() == ROOTFS_VERSION

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun extractZstdTar(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
        val actual = digest(temporary)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val BB_URL = "http://127.0.0.1:38886"
        private const val BB_DATA_DIR = "/root/.bb"
        private const val BB_START = "/root/.bb-npm/bin/bb-app --data-dir $BB_DATA_DIR --server-bind-host 127.0.0.1 --no-in-app-updates"
        private const val BB_START_COMMAND = "export PATH=/root/.bb-npm/bin:\$PATH; exec $BB_START"
        private const val TOOLS_VERSION = "git-python-gcc10-v2"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        // The same pinned, checksummed Core bundle the original app downloads (Ubuntu 20.04 arm64 + Node.js 24).
        private const val RUNTIME_BASE_URL = "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"
        private const val CORE_FILE = "pocketdev-core-arm64-2026.09.5.tar.zst"
        private const val CORE_SHA256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012"
        private const val CORE_BYTES = 72_185_773L
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
