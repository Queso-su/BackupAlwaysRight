package com.quesox.bar

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

import java.util.Locale.getDefault
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// 配置数据类
data class BackupConfig(
    var backupEnabled: Boolean = true,           // 是否启用自动备份
    var backupInterval: String = "30m",          // 自动备份间隔（支持1m,1h,1d等格式）
    var maxBackups: Int = 20,                    // 最大备份数量
    var noticeTime: Int = 3,                     // 备份前提示时间（秒），0表示不提示
    var shutdownDelay: Int = 15,                 // 备份完成后关闭服务器延迟（秒）
    var backupPaths: String = "backups",         // 备份保存路径（多个用;分隔）
    var backupNameFormat: String = "{type}_{date}_{time}", // 备份包名称格式
    var notifyPlayers: Boolean = false,           // 备份时是否通知玩家
    var verifyBackup: Boolean = true,            // 是否验证备份完整性
    var smartBackup: Boolean = true,             // 是否智能备份（无更改时跳过）
    var compressionLevel: Int = 3,               // 压缩级别 0-9（0=不压缩，1=最快，9=最好）
    var minBackupSizeMB: Long = 5,              // 最小备份大小MB（低于此值认为世界无变化）
    var requireSignificantChange: Boolean = false, // 是否需要显著变化
    var changeThreshold: Double = 0.01,          // 变化阈值（百分比）
    var minChangedFiles: Int = 5,                // 最小变化文件数
    var debugMode: Boolean = false,              // 调试模式（控制日志详细程度）
    var backupFolders: String = "world" // 要备份的文件夹（多个用;分隔）
)

// 文件更改信息类
data class FileChangeInfo(
    val path: String,
    val lastModified: Long,
    val size: Long,
    val hash: String? = null
)

// 世界状态类
data class WorldState(
    val lastBackupTime: Long = 0,
    val files: Map<String, FileChangeInfo> = emptyMap(),
    val totalSize: Long = 0,
    val totalFiles: Int = 0,
    val folderHashes: Map<String, String> = emptyMap()
)

// 世界文件夹信息
data class WorldFolder(
    val name: String,
    val path: Path,
    val enabled: Boolean = true
)

object BackupManager {
    private var backupScheduler: ScheduledExecutorService? = null
    private lateinit var server: MinecraftServer
    private val backupDirs = mutableListOf<Path>()
    private lateinit var config: BackupConfig
    private lateinit var worldFolders: List<WorldFolder>

    // 用于异步备份的线程池
    private val backupExecutor = Executors.newSingleThreadExecutor()

    // 用于服务器关闭的标记
    private var shutdownAfterBackup = false

    // 世界状态记录（用于智能备份）
    private var lastWorldState: WorldState? = null
    private val worldStateFile = File("backup_world_state.dat")
    private var hasSignificantChange = false

    // 备份进度跟踪
    private var currentBackupProgress = AtomicInteger(0)
    private var currentBackupTotal = AtomicInteger(0)
    private var currentBackupSize = AtomicLong(0)
    private var currentBackupStartTime = 0L

    fun initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { srv ->
            server = srv

            // 初始化语言管理器
            LanguageManager.initialize(server.runDirectory)

            // 从配置文件读取设置
            loadConfig()

            // 初始化备份目录
            initBackupDirs()

            // 初始化世界文件夹
            initWorldFolders()

            // 加载世界状态
            loadWorldState()

            // 启动定时备份
            startScheduledBackup()

            // 发送消息到控制台（使用翻译）
            server.sendMessage(LanguageManager.tr("backupalwaysright.mod_initialized"))
            val intervalMinutes = parseTimeInterval(config.backupInterval)
            server.sendMessage(LanguageManager.tr("backupalwaysright.system_started", intervalMinutes))

            backupDirs.forEachIndexed { _, dir ->
                server.sendMessage(LanguageManager.tr("backupalwaysright.backup_path", dir.toAbsolutePath()))
            }

            if (config.smartBackup) {
                server.sendMessage(LanguageManager.tr("backupalwaysright.smart_backup_enabled"))
            }
            if (config.debugMode) {
                server.sendMessage(LanguageManager.tr("backupalwaysright.debug_mode_enabled"))
                server.sendMessage(LanguageManager.tr("backupalwaysright.backup_folders", config.backupFolders))
                if (config.smartBackup) {
                    server.sendMessage(LanguageManager.tr("backupalwaysright.change_threshold", (config.changeThreshold * 100).format(2)))
                    server.sendMessage(LanguageManager.tr("backupalwaysright.min_changed_files", config.minChangedFiles))
                }
            }


        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            stopScheduledBackup()
            saveWorldState()
        }
    }



    private fun initBackupDirs() {
        backupDirs.clear()
        val paths = config.backupPaths.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        if (paths.isEmpty()) {
            // 使用默认路径
            val defaultPath = server.runDirectory.resolve("backups")
            backupDirs.add(defaultPath)
            Files.createDirectories(defaultPath)
        } else {
            paths.forEach { pathStr ->
                try {
                    val path = resolveBackupPath(pathStr)
                    Files.createDirectories(path)
                    backupDirs.add(path)
                } catch (e: Exception) {
                    server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.backup_dir_failed", pathStr, it) }?.string))
                }
            }
        }
    }

    private fun resolveBackupPath(path: String): Path {
        return try {
            if (path.startsWith("/") || path.contains(":\\") || path.startsWith("\\\\")) {
                Paths.get(path)
            } else {
                server.runDirectory.resolve(path)
            }
        } catch (_: Exception) {
            server.runDirectory.resolve("backups")
        }
    }

    private fun initWorldFolders() {
        val folders = mutableListOf<WorldFolder>()
        val folderNames = config.backupFolders.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        folderNames.forEach { folderName ->
            val path = server.runDirectory.resolve(folderName)
            if (Files.exists(path)) {
                folders.add(WorldFolder(folderName, path))
            } else {
                if (config.debugMode) {
                    server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.folder_not_found", folderName).string))
                }
            }
        }

        worldFolders = folders

        if (config.debugMode && folders.isNotEmpty()) {
            server.sendMessage(Text.literal("§a" + LanguageManager.tr("backupalwaysright.folders_loaded", folders.size).string))
        }
    }

    private fun loadConfig() {
        val configFile = File("config/bar.conf")
        if (configFile.exists()) {
            try {
                val props = mutableMapOf<String, String>()
                configFile.readLines().forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        val parts = trimmedLine.split("=", limit = 2)
                        if (parts.size == 2) {
                            props[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }

                config = BackupConfig(
                    backupEnabled = props.getOrDefault("backupEnabled", "true").toBoolean(),
                    backupInterval = props.getOrDefault("backupInterval", "30m"),
                    maxBackups = props.getOrDefault("maxBackups", "10").toInt(),
                    noticeTime = props.getOrDefault("noticeTime", "3").toInt(),
                    shutdownDelay = props.getOrDefault("shutdownDelay", "5").toInt(),
                    backupPaths = props.getOrDefault("backupPaths", "backups"),
                    backupNameFormat = props.getOrDefault("backupNameFormat", "{type}_{date}_{time}"),
                    notifyPlayers = props.getOrDefault("notifyPlayers", "true").toBoolean(),
                    verifyBackup = props.getOrDefault("verifyBackup", "true").toBoolean(),
                    smartBackup = props.getOrDefault("smartBackup", "true").toBoolean(),
                    compressionLevel = props.getOrDefault("compressionLevel", "3").toInt(),
                    minBackupSizeMB = props.getOrDefault("minBackupSizeMB", "10").toLong(),
                    requireSignificantChange = props.getOrDefault("requireSignificantChange", "true").toBoolean(),
                    changeThreshold = props.getOrDefault("changeThreshold", "0.01").toDouble(),
                    minChangedFiles = props.getOrDefault("minChangedFiles", "5").toInt(),
                    debugMode = props.getOrDefault("debugMode", "false").toBoolean(),
                    backupFolders = props.getOrDefault("backupFolders", "world;world_nether;world_the_end")
                )
            } catch (e: Exception) {
                server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.config_read_failed", it) }?.string))
                config = BackupConfig()
                saveConfig()
            }
        } else {
            config = BackupConfig()
            saveConfig()
        }
    }

    private fun saveConfig() {
        val configDir = File("config")
        configDir.mkdirs()

        try {
            val configContent = """
                # Form Backup Always Right Mods
                # Backup system configuration file (bar.conf)
                # 备份系统配置文件 (bar.conf)
                # Changes require /bar reload or server restart to take effect
                # 修改配置后需要/bar reload 或者重启服务器才能生效

                # ========================================
                # ----------------Language Settings----------------
                # ----------------语言设置----------------
                # ========================================
                # Language setting (auto, en_us, zh_cn)
                # Server restart required to take effect
                # 语言设置 (auto, en_us, zh_cn) 需要重启服务器才会生效
                # auto: Automatically select based on system language
                # auto: 自动根据系统语言选择
                language=${LanguageManager.getCurrentLanguage().code}
                
                # ========================================
                # ----------------General------------------
                # ----------------常规------------------
                # ========================================
                # Folders to backup (multiple separated by ;)
                # 要备份的文件夹（多个用;分隔）
                # Example :  world;world_nether;world_the_end
                backupFolders=${config.backupFolders}  
                
                # Enable automatic backup (true/false)
                # 是否启用自动备份 (true/false)
                backupEnabled=${config.backupEnabled}
                
                # Backup save paths (multiple separated by ;)
                # 备份保存路径（多个用;分隔）
                # Example: ./backups ; backups ; D:\\backups; 
                # AND SMB: \\192.168.0.105\Base\Backups ;
                backupPaths=${config.backupPaths}
                
                # Automatic backup interval (supports units: m=minutes, h=hours, d=days)
                # 自动备份间隔（支持单位：m=分钟，h=小时，d=天）
                backupInterval=${config.backupInterval}
                
                # Backup package name format
                # 备份包名称格式
                backupNameFormat=${config.backupNameFormat}
                
                # Maximum number of backups
                # 最大备份数量
                maxBackups=${config.maxBackups}
                
                # Notify players during backup (true/false)
                # 备份时是否通知玩家 (true/false)
                notifyPlayers=${config.notifyPlayers}
                
                # Notice time before backup (seconds)
                # 备份前提示时间 (秒)
                noticeTime=${config.noticeTime}
                
                # Server shutdown delay after backup (seconds)
                # 备份完成后关闭服务器延迟 (秒)
                shutdownDelay=${config.shutdownDelay}
                
                # ========================================
                # ----------------Advanced------------------
                # ----------------进阶------------------
                # ========================================
                # Verify backup integrity (true/false)
                # 是否验证备份完整性 (true/false)
                verifyBackup=${config.verifyBackup}
                
                # Enable smart backup (Files changed will be begin backup,else jump backup until next change)
                # 是否智能备份  ( 文件变化时就会自动备份，如果不变化就会跳过)
                smartBackup=${config.smartBackup}
                
                # When smart backup is enabled, require significant changes
                # 当智能备份启用时，是否要求变化是显著的
                requireSignificantChange=${config.requireSignificantChange}
                
                # Significant change threshold
                # 显著变化阈值
                # (0 - 1.00) *100%
                changeThreshold=${config.changeThreshold}
                
                # Minimum number of changed files for significant change
                # 显著最小变化文件数
                minChangedFiles=${config.minChangedFiles}
                
                # Compression level (0-9)
                # 压缩级别 (0-9)
                compressionLevel=${config.compressionLevel}
                
                # Minimum backup size in MB
                # 最小备份大小MB
                minBackupSizeMB=${config.minBackupSizeMB}
                
                # Debug mode (true/false)
                # 调试模式 (true/false)
                debugMode=${config.debugMode}
""".trimIndent()


            File("config/bar.conf").writeText(configContent)
        } catch (e: Exception) {
            server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.config_save_failed", it) }?.string))
        }
    }

    /**
     * 将时间字符串转换为分钟数
     */
    private fun parseTimeInterval(timeStr: String): Long {
        var totalMinutes = 0L
        val currentNumber = StringBuilder()

        for (char in timeStr.lowercase(getDefault())) {
            when {
                char.isDigit() -> currentNumber.append(char)
                char == 'm' -> {
                    totalMinutes += currentNumber.toString().toLongOrNull() ?: 0
                    currentNumber.clear()
                }
                char == 'h' -> {
                    totalMinutes += (currentNumber.toString().toLongOrNull() ?: 0) * 60
                    currentNumber.clear()
                }
                char == 'd' -> {
                    totalMinutes += (currentNumber.toString().toLongOrNull() ?: 0) * 24 * 60
                    currentNumber.clear()
                }
            }
        }

        // 如果没有单位，假设是分钟
        if (currentNumber.isNotEmpty()) {
            totalMinutes += currentNumber.toString().toLongOrNull() ?: 0
        }

        return totalMinutes.coerceAtLeast(1) // 最少1分钟
    }

    /**
     * 检查世界是否有显著变化（增强版）
     */
    private fun checkWorldChanges(): WorldChangeResult {
        val lastState = lastWorldState ?: return WorldChangeResult(
            hasSignificantChange = true,
            reason = LanguageManager.tr("backupalwaysright.first_check").string
        )

        val currentState = scanWorldState()
        val changedFiles = mutableListOf<String>()
        var changedSize: Long = 0
        var changedCount = 0

        // 检查文件变化
        currentState.files.forEach { (path, currentInfo) ->
            val lastInfo = lastState.files[path]

            if (lastInfo == null) {
                // 新文件
                changedFiles.add("[${LanguageManager.tr("backupalwaysright.new").string}] $path")
                changedSize += currentInfo.size
                changedCount++
            } else if (currentInfo.lastModified != lastInfo.lastModified ||
                currentInfo.size != lastInfo.size) {
                // 修改的文件
                if (config.debugMode) {
                    changedFiles.add("[${LanguageManager.tr("backupalwaysright.modified").string}] $path (${LanguageManager.tr("backupalwaysright.size").string}: ${formatSize(lastInfo.size)} -> ${formatSize(currentInfo.size)})")
                }
                changedSize += Math.abs(currentInfo.size - lastInfo.size)
                changedCount++
            }
        }

        // 检查删除的文件
        lastState.files.forEach { (path, _) ->
            if (!currentState.files.containsKey(path)) {
                if (config.debugMode) {
                    changedFiles.add("[${LanguageManager.tr("backupalwaysright.deleted").string}] $path")
                }
                changedCount++
            }
        }

        // 判断是否有显著变化
        val hasSignificantChange = if (config.requireSignificantChange) {
            // 检查变化是否达到阈值
            val sizeRatio = if (lastState.totalSize > 0) {
                changedSize.toDouble() / lastState.totalSize
            } else {
                1.0
            }

            val hasSizeChange = changedSize >= config.minBackupSizeMB * 1024 * 1024
            val hasRatioChange = sizeRatio >= config.changeThreshold
            val hasMinFiles = changedCount >= config.minChangedFiles

            val significant = hasSizeChange || (hasRatioChange && hasMinFiles)

            if (config.debugMode && significant) {
                server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.significant_change",
                    formatSize(changedSize), changedCount, (sizeRatio * 100).format(2)).string))
            }

            significant
        } else {
            // 只要有变化就算
            changedCount > 0
        }

        return WorldChangeResult(
            hasSignificantChange = hasSignificantChange,
            changedSize = changedSize,
            changedCount = changedCount,
            changedFiles = if (config.debugMode && changedFiles.size <= 10) changedFiles else emptyList(),
            totalFiles = changedFiles.size,
            reason = if (hasSignificantChange)
                LanguageManager.tr("backupalwaysright.significant_change_detected").string
            else LanguageManager.tr("backupalwaysright.insufficient_change").string
        )
    }

    /**
     * 扫描当前世界状态
     */
    private fun scanWorldState(): WorldState {
        val files = mutableMapOf<String, FileChangeInfo>()
        val folderHashes = mutableMapOf<String, String>()
        var totalSize: Long = 0
        var totalFiles = 0

        worldFolders.forEach { worldFolder ->
            val folder = worldFolder.path.toFile()
            if (folder.exists()) {
                // 计算文件夹哈希（用于检测文件夹结构变化）
                if (config.debugMode) {
                    folderHashes[worldFolder.name] = calculateFolderHash(folder)
                }

                folder.walkTopDown().forEach { file ->
                    if (file.isFile && !file.name.equals("session.lock")) {
                        val relativePath = "${worldFolder.name}/${folder.toPath().relativize(file.toPath())}"
                        files[relativePath] = FileChangeInfo(
                            path = relativePath,
                            lastModified = file.lastModified(),
                            size = file.length()
                        )
                        totalSize += file.length()
                        totalFiles++
                    }
                }
            }
        }

        return WorldState(
            lastBackupTime = System.currentTimeMillis(),
            files = files,
            totalSize = totalSize,
            totalFiles = totalFiles,
            folderHashes = folderHashes
        )
    }

    /**
     * 计算文件夹哈希（简单版）
     */
    private fun calculateFolderHash(folder: File): String {
        val hashString = buildString {
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                append(file.name)
                append(file.lastModified())
                if (file.isFile) {
                    append(file.length())
                }
            }
        }
        return hashString.hashCode().toString()
    }

    /**
     * 保存世界状态到文件
     */
    private fun saveWorldState() {
        try {
            val state = lastWorldState ?: return
            val data = buildString {
                appendLine(state.lastBackupTime)
                appendLine(state.totalSize)
                appendLine(state.totalFiles)
                if (config.debugMode) {
                    state.folderHashes.forEach { (folder, hash) ->
                        appendLine("FOLDER:$folder:$hash")
                    }
                }
                state.files.forEach { (path, info) ->
                    appendLine("FILE:$path:${info.lastModified}:${info.size}")
                }
            }
            worldStateFile.writeText(data)
        } catch (e: Exception) {
            if (config.debugMode) {
                server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.save_state_failed", it) }?.string))
            }
        }
    }

    /**
     * 从文件加载世界状态
     */
    private fun loadWorldState() {
        try {
            if (!worldStateFile.exists()) {
                lastWorldState = WorldState()
                return
            }

            val lines = worldStateFile.readLines()
            if (lines.size < 3) {
                lastWorldState = WorldState()
                return
            }

            val lastBackupTime = lines[0].toLongOrNull() ?: 0
            val totalSize = lines[1].toLongOrNull() ?: 0
            val totalFiles = lines[2].toIntOrNull() ?: 0
            val files = mutableMapOf<String, FileChangeInfo>()
            val folderHashes = mutableMapOf<String, String>()

            for (i in 3 until lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("FOLDER:") -> {
                        val parts = line.removePrefix("FOLDER:").split(":", limit = 2)
                        if (parts.size == 2) {
                            folderHashes[parts[0]] = parts[1]
                        }
                    }
                    line.startsWith("FILE:") -> {
                        val parts = line.removePrefix("FILE:").split(":", limit = 3)
                        if (parts.size == 3) {
                            files[parts[0]] = FileChangeInfo(
                                path = parts[0],
                                lastModified = parts[1].toLongOrNull() ?: 0,
                                size = parts[2].toLongOrNull() ?: 0
                            )
                        }
                    }
                }
            }

            lastWorldState = WorldState(lastBackupTime, files, totalSize, totalFiles, folderHashes)
        } catch (e: Exception) {
            if (config.debugMode) {
                server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.load_state_failed", it) }?.string))
            }
            lastWorldState = WorldState()
        }
    }

    private fun startScheduledBackup() {
        if (backupScheduler != null) {
            backupScheduler?.shutdown()
        }

        if (config.backupEnabled) {
            val intervalMinutes = parseTimeInterval(config.backupInterval)
            backupScheduler = Executors.newSingleThreadScheduledExecutor()
            backupScheduler?.scheduleAtFixedRate(
                { createBackup() },
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES
            )
            if (config.debugMode) {
                server.sendMessage(Text.literal("§a" + LanguageManager.tr("backupalwaysright.scheduled_backup_started", intervalMinutes).string))
            }
        }
    }

    private fun stopScheduledBackup() {
        backupScheduler?.shutdown()
        backupScheduler = null
    }

    /**
     * 根据格式生成备份文件名
     */
    private fun generateBackupName(manual: Boolean): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val timeFormat = SimpleDateFormat("HH-mm-ss")
        val now = Date()

        val backupType = if (manual) "manual" else "auto"
        val date = dateFormat.format(now)
        val time = timeFormat.format(now)

        return config.backupNameFormat
            .replace("{type}", backupType)
            .replace("{date}", date)
            .replace("{time}", time)
            .replace("{server}", "server")
    }

    fun createBackup(manual: Boolean = false, shutdown: Boolean = false): String {
        if (!config.backupEnabled && !manual) {
            return LanguageManager.tr("backupalwaysright.backup_disabled").string
        }

        // 智能备份检查（仅对自动备份有效）
        if (!manual && config.smartBackup) {
            if (!hasSignificantChange) {
                val result = checkWorldChanges()
                if (!result.hasSignificantChange) {
                    val message = if (config.debugMode) "§7${result.reason}，${LanguageManager.tr("backupalwaysright.skip_backup").string}"
                    else "§7" + LanguageManager.tr("backupalwaysright.no_change_skip").string
                    server.sendMessage(Text.literal(message))
                    return message
                }
            }
        }

        // 设置关闭标记
        shutdownAfterBackup = shutdown

        return try {
            val notify = config.notifyPlayers

            // 如果设置了提示时间，发送提示消息
            if (config.noticeTime > 0 && manual && notify) {
                val noticeMessage = LanguageManager.tr("backupalwaysright.countdown_backup", config.noticeTime).string
                server.playerManager.broadcast(Text.literal(noticeMessage), false)

                // 延迟执行备份（异步）
                Thread {
                    Thread.sleep((config.noticeTime * 1000).toLong())
                    executeBackupProcess(manual, notify)
                }.start()

                return noticeMessage
            } else {
                // 立即开始备份
                executeBackupProcess(manual, notify)
                return LanguageManager.tr("backupalwaysright.backup_task_started").string
            }
        } catch (e: Exception) {
            val errorMsg = e.message?.let { LanguageManager.tr("backupalwaysright.backup_failed", it) }!!.string
            server.sendMessage(Text.literal("§c$errorMsg"))
            errorMsg
        }
    }

    /**
     * 执行备份流程
     */
    private fun executeBackupProcess(manual: Boolean, notify: Boolean) {
        // 确保服务器保存所有数据（同步执行）
        server.playerManager.saveAllPlayerData()
        server.save(true, true, true)

        // 发送开始备份消息
        server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_world_start").string))

        // 修改条件：手动备份始终通知，自动备份根据配置通知
        val shouldNotifyPlayers = manual || notify
        if (shouldNotifyPlayers) {
            server.playerManager.broadcast(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_world_compressing").string), false)
        }

        // 异步执行压缩备份
        backupExecutor.submit {
            try {
                currentBackupStartTime = System.currentTimeMillis()
                currentBackupProgress.set(0)
                currentBackupSize.set(0)

                // 先扫描当前世界状态（用于智能备份记录）
                val newWorldState = scanWorldState()

                // 创建备份
                val results = mutableListOf<String>()
                backupDirs.forEachIndexed { index, backupDir ->
                    try {
                        val result = createCompressedBackupAsync(manual, backupDir)
                        results.add(LanguageManager.tr("backupalwaysright.backup_path_result", index + 1, result).string)
                    } catch (e: Exception) {
                        results.add(LanguageManager.tr("backupalwaysright.backup_path_failed", index + 1, e.message!!).string)
                    }
                }

                // 更新世界状态并重置变化标志
                lastWorldState = newWorldState
                hasSignificantChange = false
                saveWorldState()

                // 发送结果
                results.forEach { result ->
                    server.sendMessage(Text.literal("§a$result"))
                }

                // 修改条件：手动备份始终通知，自动备份根据配置通知
                if (shouldNotifyPlayers) {
                    server.playerManager.broadcast(Text.literal("§a" + LanguageManager.tr("backupalwaysright.backup_world_completed").string), false)
                }

                // 清理旧的备份文件
                cleanupOldBackups()

                // 如果设置了关闭服务器，延迟后关闭
                if (shutdownAfterBackup) {
                    val shutdownMsg = "§c" + LanguageManager.tr("backupalwaysright.shutdown_scheduled", config.shutdownDelay).string
                    server.sendMessage(Text.literal(shutdownMsg))

                    // 修改条件：手动备份始终通知，自动备份根据配置通知
                    if (shouldNotifyPlayers) {
                        server.playerManager.broadcast(Text.literal(shutdownMsg), false)
                    }

                    Thread.sleep((config.shutdownDelay * 1000).toLong())
                    server.stop(false) // 正常关闭服务器
                }
            } catch (e: Exception) {
                val errorMsg = LanguageManager.tr("backupalwaysright.backup_async_failed", e.message!!).string
                server.sendMessage(Text.literal("§c$errorMsg"))
            }
        }
    }

    private fun createCompressedBackupAsync(manual: Boolean, backupDir: Path): String {
        val backupName = generateBackupName(manual)
        val backupFile = backupDir.resolve("$backupName.zip").toFile()

        // 检查是否有可备份的文件夹
        val existingFolders = worldFolders.filter { it.enabled && Files.exists(it.path) }

        if (existingFolders.isEmpty()) {
            return LanguageManager.tr("backupalwaysright.no_world_folders").string
        }

        // 计算需要备份的文件总数
        var totalFiles = 0
        existingFolders.forEach { folder ->
            totalFiles += countFiles(folder.path.toFile())
        }
        currentBackupTotal.set(totalFiles)

        // 创建ZIP压缩文件
        var success = false
        var attempts = 0
        val maxAttempts = 2

        while (!success && attempts < maxAttempts) {
            attempts++
            try {
                FileOutputStream(backupFile).use { fileOut ->
                    ZipOutputStream(fileOut).use { zipOut ->
                        // 设置压缩级别
                        zipOut.setLevel(config.compressionLevel)

                        existingFolders.forEach { worldFolder ->
                            val folder = worldFolder.path.toFile()
                            val startTime = System.currentTimeMillis()

                            if (config.debugMode) {
                                server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_world_compressing_file", worldFolder.name).string))
                            }

                            // 压缩文件夹
                            compressFolderToZip(folder, worldFolder.name, zipOut)

                            if (config.debugMode) {
                                val elapsed = System.currentTimeMillis() - startTime
                                server.sendMessage(Text.literal("§a" + LanguageManager.tr("backupalwaysright.backup_world_file_completed", worldFolder.name, elapsed).string))
                            }
                        }
                    }
                }

                // 验证备份完整性
                if (config.verifyBackup) {
                    server.sendMessage(Text.literal("§a" + LanguageManager.tr("backupalwaysright.verify_backup").string))
                    if (verifyBackup(backupFile)) {
                        success = true
                        server.sendMessage(Text.literal("§a" + LanguageManager.tr("backupalwaysright.verify_success").string))
                    } else {
                        server.sendMessage(Text.literal("§c" + LanguageManager.tr("backupalwaysright.verify_failed").string))
                        backupFile.delete()
                        if (attempts >= maxAttempts) {
                            throw Exception(LanguageManager.tr("backupalwaysright.max_retry_exceeded").string)
                        }
                    }
                } else {
                    success = true
                }
            } catch (e: Exception) {
                server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.backup_process_error", it) }?.string))
                if (attempts >= maxAttempts) {
                    throw e
                }
            }
        }

        val fileSize = backupFile.length() / (1024 * 1024) // MB
        val elapsedTime = (System.currentTimeMillis() - currentBackupStartTime) / 1000

        return LanguageManager.tr("backupalwaysright.backup_created", backupName, fileSize, elapsedTime).string
    }

    /**
     * 递归压缩文件夹到ZIP
     */
    private fun compressFolderToZip(folder: File, basePath: String, zipOut: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            val entryName = "$basePath/${file.name}"

            // 跳过 session.lock 文件
            if (file.name == "session.lock") {
                return@forEach
            }

            if (file.isDirectory) {
                // 对于目录，添加一个目录条目
                zipOut.putNextEntry(ZipEntry("$entryName/"))
                zipOut.closeEntry()

                // 递归压缩子目录
                compressFolderToZip(file, entryName, zipOut)
            } else {
                try {
                    zipOut.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { fileIn ->
                        fileIn.copyTo(zipOut, 8192)
                    }
                    zipOut.closeEntry()
                    currentBackupSize.addAndGet(file.length())
                } catch (_: Exception) {
                    if (config.debugMode) {
                        server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_locked_file", file.absolutePath).string))
                    }
                }

                currentBackupProgress.incrementAndGet()

                // 每处理100个文件显示一次进度
                if ( currentBackupProgress.get() % 100 == 0) {
                    val progress = (currentBackupProgress.get() * 100 / currentBackupTotal.get()).coerceIn(0, 100)
                    val processedMB = currentBackupSize.get() / (1024 * 1024)
                    server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_progress", progress, processedMB).string))
                }
            }
        }
    }

    /**
     * 验证备份文件完整性
     */
    private fun verifyBackup(backupFile: File): Boolean {
        return try {
            ZipFile(backupFile).use { zip ->
                val entries = zip.entries()
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            while (input.read(buffer) > 0) {
                                // 只是读取数据，不做处理
                            }
                        }
                        entryCount++
                    }
                }
                if (config.debugMode) {
                    server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.verify_file_count", entryCount).string))
                }
                entryCount > 0 // 至少应该有一些文件
            }
            true
        } catch (e: Exception) {
            server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.verify_exception", it) }?.string))
            false
        }
    }

    /**
     * 计算文件夹中的文件数量
     */
    private fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach {
            if (it.isFile && !it.name.equals("session.lock")) {
                count++
            }
        }
        return count
    }

    private fun cleanupOldBackups() {
        backupDirs.forEachIndexed { index, backupDir ->
            try {
                val backups = backupDir.toFile().listFiles { file ->
                    file.isFile && file.name.endsWith(".zip")
                } ?: return@forEachIndexed

                if (backups.size > config.maxBackups) {
                    backups.sortedBy { it.lastModified() }
                        .take(backups.size - config.maxBackups)
                        .forEach {
                            it.delete()
                            if (config.debugMode) {
                                server.sendMessage(Text.literal("§7" + LanguageManager.tr("backupalwaysright.backup_cleaning", it.name).string))
                            }
                        }
                }
            } catch (e: Exception) {
                server.sendMessage(Text.literal("§c" + e.message?.let { LanguageManager.tr("backupalwaysright.backup_clean_failed", index + 1, it) }?.string))
            }
        }
    }

    // 命令相关方法
    fun toggleBackup(enabled: Boolean): String {
        config.backupEnabled = enabled
        saveConfig()

        if (enabled) {
            startScheduledBackup()
            return "§a" + LanguageManager.tr("backupalwaysright.auto_backup_enabled").string
        } else {
            stopScheduledBackup()
            return "§c" + LanguageManager.tr("backupalwaysright.auto_backup_disabled").string
        }
    }
    /*
    fun setBackupPaths(paths: String): String {
        config.backupPaths = paths
        saveConfig()

        // 重新初始化备份目录
        initBackupDirs()

        return buildString {
            append("§a" + LanguageManager.tr("backupalwaysright.backup_notify_path").string + "\n")
            backupDirs.forEachIndexed { index, dir ->
                append("§7" + LanguageManager.tr("backupalwaysright.backup_path_item", index + 1, dir.toAbsolutePath()).string + "\n")
            }
        }
    }

    // 设置备份间隔（分钟）
    fun setBackupIntervalMinutes(minutes: Int): String {
        val intervalStr = "${minutes}m"
        config.backupInterval = intervalStr
        saveConfig()

        val totalMinutes = parseTimeInterval(intervalStr)
        startScheduledBackup()
        return "§a" + LanguageManager.tr("backupalwaysright.backup_interval_minutes_set", minutes, totalMinutes).string
    }

    // 设置备份间隔（小时）
    fun setBackupIntervalHours(hours: Int): String {
        val intervalStr = "${hours}h"
        config.backupInterval = intervalStr
        saveConfig()

        val totalMinutes = parseTimeInterval(intervalStr)
        startScheduledBackup()
        return "§a" + LanguageManager.tr("backupalwaysright.backup_interval_hours_set", hours, totalMinutes).string
    }

    // 设置备份间隔（天）
    fun setBackupIntervalDays(days: Int): String {
        val intervalStr = "${days}d"
        config.backupInterval = intervalStr
        saveConfig()

        val totalMinutes = parseTimeInterval(intervalStr)
        startScheduledBackup()
        return "§a" + LanguageManager.tr("backupalwaysright.backup_interval_days_set", days, totalMinutes).string
    }

    // 重新加载配置文件
    fun reloadConfig(): String {
        try {
            // 保存当前的世界状态，避免重新加载时丢失
            val oldState = lastWorldState

            // 重新加载配置
            loadConfig()

            // 重新初始化备份目录
            initBackupDirs()

            // 重新初始化世界文件夹
            initWorldFolders()

            // 恢复世界状态
            if (oldState != null) {
                lastWorldState = oldState
            } else {
                loadWorldState()
            }

            // 重启定时备份
            stopScheduledBackup()
            startScheduledBackup()

            // 输出状态信息
            val intervalMinutes = parseTimeInterval(config.backupInterval)
            val currentLang = LanguageManager.getCurrentLanguage()

            return buildString {
                append("§a" + LanguageManager.tr("backupalwaysright.config_reloaded").string + "\n")
                append("§7" + LanguageManager.tr("backupalwaysright.current_config").string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.language_setting", currentLang.code).string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.auto_backup", if (config.backupEnabled) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.backup_interval", config.backupInterval, intervalMinutes).string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.backup_path_count", backupDirs.size).string + "\n")
                backupDirs.forEachIndexed { index, dir ->
                    append("§7  " + LanguageManager.tr("backupalwaysright.backup_path_item", index + 1, dir.toAbsolutePath()).string + "\n")
                }
                append("§7- " + LanguageManager.tr("backupalwaysright.smart_backup", if (config.smartBackup) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.debug_mode", if (config.debugMode) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
                append("§7- " + LanguageManager.tr("backupalwaysright.backup_folders", config.backupFolders).string + "\n")
            }
        } catch (e: Exception) {
            val errorMsg = e.message?.let { LanguageManager.tr("backupalwaysright.reload_failed", it) }!!.string
            server.sendMessage(Text.literal("§c$errorMsg"))
            return "§c$errorMsg"
        }
    }

    fun setDebugMode(enabled: Boolean): String {
        config.debugMode = enabled
        saveConfig()
        return if (enabled) "§a" + LanguageManager.tr("backupalwaysright.debug_mode_enabled").string
        else "§c" + LanguageManager.tr("backupalwaysright.debug_mode_disabled").string
    }

    fun setBackupFolders(folders: String): String {
        config.backupFolders = folders
        saveConfig()

        // 重新初始化世界文件夹
        initWorldFolders()

        return "§a" + LanguageManager.tr("backupalwaysright.backup_folders_set", folders).string
    }

    fun setNoticeTime(seconds: Int): String {
        config.noticeTime = seconds.coerceIn(0, 300)
        saveConfig()
        return "§a" + LanguageManager.tr("backupalwaysright.notice_time_set", config.noticeTime).string
    }

    fun setShutdownDelay(seconds: Int): String {
        config.shutdownDelay = seconds.coerceIn(1, 60)
        saveConfig()
        return "§a" + LanguageManager.tr("backupalwaysright.shutdown_delay_set", config.shutdownDelay).string
    }

    // 添加语言设置方法
    fun setLanguage(lang: String): String {
        return try {
            val language = LanguageManager.Language.fromCode(lang)
            val success = LanguageManager.setLanguage(language, server.runDirectory)

            if (success) {
                "§a" + LanguageManager.tr("backupalwaysright.language_set", language.code).string
            } else {
                "§c" + LanguageManager.tr("backupalwaysright.language_change_failed").string
            }
        } catch (e: Exception) {
            "§c" + LanguageManager.tr("backupalwaysright.language_invalid", lang).string
        }
    }
    */
    fun listBackups(): List<String> {
        val backups = mutableListOf<String>()

        backupDirs.forEachIndexed { index, backupDir ->
            val dirBackups = backupDir.toFile().listFiles { file ->
                file.isFile && file.name.endsWith(".zip")
            } ?: emptyArray()

            if (dirBackups.isNotEmpty()) {
                backups.add("§6=== " + LanguageManager.tr("backupalwaysright.backup_dir_title", index + 1, backupDir.toAbsolutePath()).string + " ===")

                dirBackups.sortedByDescending { it.lastModified() }
                    .take(10) // 只显示最近的10个
                    .forEach {
                        val size = it.length() / (1024 * 1024)
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(it.lastModified()))
                        backups.add("§7${it.name.substringBeforeLast(".zip")} (${size}MB, $date)")
                    }

                if (dirBackups.size > 10) {
                    backups.add("§7... " + LanguageManager.tr("backupalwaysright.more_backups", dirBackups.size - 10).string)
                }
            }
        }

        if (backups.isEmpty()) {
            backups.add("§7" + LanguageManager.tr("backupalwaysright.no_backups_found").string)
        }

        return backups
    }

    fun getBackupStatus(): String {
        val intervalMinutes = parseTimeInterval(config.backupInterval)
        val currentLang = LanguageManager.getCurrentLanguage()

        return buildString {
            append("§6=== " + LanguageManager.tr("backupalwaysright.backup_system_status").string + " ===\n")
            append("§7" + LanguageManager.tr("backupalwaysright.language_setting", currentLang.code).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.auto_backup_status", if (config.backupEnabled) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.backup_interval_status", config.backupInterval, intervalMinutes).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.backup_path_count_status", backupDirs.size).string + "\n")
            backupDirs.forEachIndexed { index, dir ->
                append("§7  " + LanguageManager.tr("backupalwaysright.backup_path_item", index + 1, dir.toAbsolutePath()).string + "\n")
            }
            append("§7" + LanguageManager.tr("backupalwaysright.smart_backup_status", if (config.smartBackup) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
            if (config.smartBackup) {
                append("§7" + LanguageManager.tr("backupalwaysright.change_threshold_status", (config.changeThreshold * 100).format(2)).string + "\n")
                append("§7" + LanguageManager.tr("backupalwaysright.min_files_status", config.minChangedFiles).string + "\n")
            }
            append("§7" + LanguageManager.tr("backupalwaysright.compression_level_status", config.compressionLevel).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.max_backups_status", config.maxBackups).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.debug_mode_status", if (config.debugMode) "§a" + LanguageManager.tr("backupalwaysright.enabled").string else "§c" + LanguageManager.tr("backupalwaysright.disabled").string).string + "\n")
            append("§7" + LanguageManager.tr("backupalwaysright.backup_folders_status", config.backupFolders).string + "\n")
        }
    }

    // 辅助函数
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${(bytes / (1024 * 1024 * 1024.0)).format(2)} GB"
            bytes >= 1024 * 1024 -> "${(bytes / (1024 * 1024.0)).format(2)} MB"
            bytes >= 1024 -> "${(bytes / 1024.0).format(2)} KB"
            else -> "$bytes B"
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}

// 世界变化结果类
data class WorldChangeResult(
    val hasSignificantChange: Boolean,
    val changedSize: Long = 0,
    val changedCount: Int = 0,
    val changedFiles: List<String> = emptyList(),
    val totalFiles: Int = 0,
    val reason: String = ""
)
