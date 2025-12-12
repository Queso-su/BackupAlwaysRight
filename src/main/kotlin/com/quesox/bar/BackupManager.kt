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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// 配置数据类
data class BackupConfig(
    var backupEnabled: Boolean = true,           // 是否启用自动备份
    var backupInterval: Int = 30,                // 自动备份间隔（分钟）
    var maxBackups: Int = 20,                    // 最大备份数量
    var noticeTime: Int = 3,                     // 备份前提示时间（秒），0表示不提示
    var shutdownDelay: Int = 15,                  // 备份完成后关闭服务器延迟（秒）
    var backupPath: String = "backups",          // 备份保存路径（相对或绝对路径）
    var backupNameFormat: String = "{type}_{date}_{time}", // 备份包名称格式
    var notifyPlayers: Boolean = true            // 备份时是否通知玩家
)

object BackupManager {
    private var backupScheduler: ScheduledExecutorService? = null
    private lateinit var server: MinecraftServer
    private lateinit var backupDir: Path
    private lateinit var config: BackupConfig

    // 用于异步备份的线程池
    private val backupExecutor = Executors.newSingleThreadExecutor()

    // 用于服务器关闭的标记
    private var shutdownAfterBackup = false

    fun initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { srv ->
            server = srv

            // 初始化备份目录
            initBackupDir()

            // 从配置文件读取设置
            loadConfig()

            // 启动定时备份
            startScheduledBackup()

            // 发送消息到控制台
            server.sendMessage(Text.literal("§a[Backup Always Right] 备份系统已启动，间隔: ${config.backupInterval}分钟"))
            server.sendMessage(Text.literal("§a备份路径: ${backupDir.toAbsolutePath()}"))
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            stopScheduledBackup()
        }
    }

    private fun initBackupDir() {
        val configFile = File("config/bar.conf")
        if (configFile.exists()) {
            try {
                // 先读取配置文件获取备份路径
                val props = mutableMapOf<String, String>()
                configFile.readLines().forEach { line ->
                    val trimmedLine = line.trim()
                    // 跳过注释行和空行
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        val parts = trimmedLine.split("=", limit = 2)
                        if (parts.size == 2) {
                            props[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }

                val backupPath = props.getOrDefault("backupPath", "backups")
                backupDir = resolveBackupPath(backupPath)
                Files.createDirectories(backupDir)
            } catch (e: Exception) {
                // 如果读取失败，使用默认路径
                backupDir = server.runDirectory.resolve("backups")
                Files.createDirectories(backupDir)
            }
        } else {
            // 配置文件不存在，使用默认路径
            backupDir = server.runDirectory.resolve("backups")
            Files.createDirectories(backupDir)
        }
    }

    private fun resolveBackupPath(path: String): Path {
        return try {
            if (path.startsWith("/") || path.contains(":\\") || path.startsWith("\\\\")) {
                // 绝对路径
                Paths.get(path)
            } else {
                // 相对路径，相对于服务器根目录
                server.runDirectory.resolve(path)
            }
        } catch (e: Exception) {
            // 路径解析失败，使用默认路径
            server.runDirectory.resolve("backups")
        }
    }

    private fun loadConfig() {
        val configFile = File("config/bar.conf")
        if (configFile.exists()) {
            try {
                val props = mutableMapOf<String, String>()
                configFile.readLines().forEach { line ->
                    val trimmedLine = line.trim()
                    // 跳过注释行和空行
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        val parts = trimmedLine.split("=", limit = 2)
                        if (parts.size == 2) {
                            props[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }

                // 解析配置值
                config = BackupConfig(
                    backupEnabled = props.getOrDefault("backupEnabled", "true").toBoolean(),
                    backupInterval = props.getOrDefault("backupInterval", "30").toInt(),
                    maxBackups = props.getOrDefault("maxBackups", "10").toInt(),
                    noticeTime = props.getOrDefault("noticeTime", "3").toInt(),
                    shutdownDelay = props.getOrDefault("shutdownDelay", "5").toInt(),
                    backupPath = props.getOrDefault("backupPath", "backups"),
                    backupNameFormat = props.getOrDefault("backupNameFormat", "{type}_{date}_{time}"),
                    notifyPlayers = props.getOrDefault("notifyPlayers", "true").toBoolean()
                )
            } catch (e: Exception) {
                server.sendMessage(Text.literal("§c读取配置文件失败，使用默认配置: ${e.message}"))
                config = BackupConfig()
                saveConfig()
            }
        } else {
            // 创建默认配置文件
            config = BackupConfig()
            saveConfig()
        }
    }

    private fun saveConfig() {
        val configDir = File("config")
        configDir.mkdirs()

        try {
            val configContent = """
                # 备份系统配置文件 (bar.conf)
                # ========================================
                # 修改配置后保存理论上能生效，但可能需要重启服务器
                # ========================================
                
                # 是否启用自动备份 (true/false)
                # 设置为 true 时，系统会按照设定的间隔自动备份
                backupEnabled=${config.backupEnabled}
                
                # 自动备份间隔 (分钟)
                # 设置自动备份的时间间隔，最小值为1分钟
                backupInterval=${config.backupInterval}
                
                # 最大备份数量
                # 当备份文件数量超过此值时，会自动删除最旧的备份
                maxBackups=${config.maxBackups}
                
                # 备份前提示时间 (秒)
                # 在执行手动备份前，提前多少秒通知玩家
                # 设置为 0 表示不提示，立即开始备份
                noticeTime=${config.noticeTime}
                
                # 备份完成后关闭服务器延迟 (秒)
                # 使用 /bar shutdown 命令时，备份完成后多少秒关闭服务器
                shutdownDelay=${config.shutdownDelay}
                
                # 备份保存路径
                # 可以是相对路径（相对于服务器根目录）或绝对路径
                # 示例: 
                #   backupPath=backups                 (默认，服务器根目录下的backups文件夹)
                #   backupPath=/home/user/backups      (绝对路径，Linux/Mac)
                #   backupPath=C:\Minecraft\backups    (绝对路径，Windows)
                #   backupPath=../backups              (相对路径，上级目录的backups文件夹)
                backupPath=${config.backupPath}
                
                # 备份包名称格式
                # 支持以下变量：
                #   {type} - 备份类型：auto（自动）或 manual（手动）
                #   {date} - 备份日期：格式为 yyyy-MM-dd
                #   {time} - 备份时间：格式为 HH-mm-ss
                #   {server} - 服务器名称（暂时固定为"server"）
                # 示例：
                #   backupNameFormat={type}_{date}_{time}       (默认：auto_2023-12-12_14-30-00)
                #   backupNameFormat=backup_{date}_{time}       (backup_2023-12-12_14-30-00)
                #   backupNameFormat={server}_{type}_{date}     (server_auto_2023-12-12)
                #   backupNameFormat={type}_backup              (auto_backup 或 manual_backup)
                backupNameFormat=${config.backupNameFormat}
                
                # 备份时是否通知玩家 (true/false)
                # 设置为 true 时，备份开始和完成时会向所有玩家发送通知
                # 设置为 false 时，只会在控制台显示备份信息
                notifyPlayers=${config.notifyPlayers}
            """.trimIndent()

            File("config/bar.conf").writeText(configContent)
        } catch (e: Exception) {
            server.sendMessage(Text.literal("§c保存配置文件失败: ${e.message}"))
        }
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
            return "备份功能已禁用"
        }

        // 设置关闭标记
        shutdownAfterBackup = shutdown

        return try {
            val notify = config.notifyPlayers

            // 如果设置了提示时间，发送提示消息
            if (config.noticeTime > 0 && manual && notify) {
                val noticeMessage = "§e服务器将在 §a${config.noticeTime}§e 秒后开始备份"
                server.playerManager.broadcast(Text.literal(noticeMessage), false)

                // 延迟执行备份（异步）
                Thread {
                    Thread.sleep((config.noticeTime * 1000).toLong())

                    // 确保服务器保存所有数据（同步执行）
                    server.playerManager.saveAllPlayerData()
                    server.save(true, true, true)

                    // 发送开始备份消息
                    server.sendMessage(Text.literal("§7开始备份世界数据..."))

                    if (notify) {
                        server.playerManager.broadcast(Text.literal("§7正在备份世界数据..."), false)
                    }

                    // 异步执行压缩备份
                    backupExecutor.submit {
                        try {
                            val result = createCompressedBackupAsync(manual)
                            server.sendMessage(Text.literal("§a$result"))

                            if (manual && notify) {
                                server.playerManager.broadcast(Text.literal("§a服务器备份已完成"), false)
                            }

                            // 清理旧的备份文件
                            cleanupOldBackups()

                            // 如果设置了关闭服务器，延迟后关闭
                            if (shutdownAfterBackup) {
                                val shutdownMsg = "§c备份完成，将在 §a${config.shutdownDelay}§c 秒后关闭服务器..."
                                server.sendMessage(Text.literal(shutdownMsg))

                                if (notify) {
                                    server.playerManager.broadcast(Text.literal(shutdownMsg), false)
                                }

                                Thread.sleep((config.shutdownDelay * 1000).toLong())
                                server.stop(false) // 正常关闭服务器
                            }
                        } catch (e: Exception) {
                            val errorMsg = "异步备份失败: ${e.message}"
                            server.sendMessage(Text.literal("§c$errorMsg"))
                        }
                    }
                }.start()

                // 立即返回，表示备份即将开始
                return "备份将在 ${config.noticeTime} 秒后开始..."
            } else {
                // 立即开始备份
                // 确保服务器保存所有数据（同步执行）
                server.playerManager.saveAllPlayerData()
                server.save(true, true, true)

                // 发送开始备份消息
                server.sendMessage(Text.literal("§7开始备份世界数据..."))

                if (manual && notify) {
                    server.playerManager.broadcast(Text.literal("§7正在备份世界数据..."), false)
                }

                // 异步执行压缩备份
                backupExecutor.submit {
                    try {
                        val result = createCompressedBackupAsync(manual)
                        server.sendMessage(Text.literal("§a$result"))

                        if (manual && notify) {
                            server.playerManager.broadcast(Text.literal("§a服务器备份已完成"), false)
                        }

                        // 清理旧的备份文件
                        cleanupOldBackups()

                        // 如果设置了关闭服务器，延迟后关闭
                        if (shutdownAfterBackup) {
                            val shutdownMsg = "§c备份完成，将在 §a${config.shutdownDelay}§c 秒后关闭服务器..."
                            server.sendMessage(Text.literal(shutdownMsg))

                            if (notify) {
                                server.playerManager.broadcast(Text.literal(shutdownMsg), false)
                            }

                            Thread.sleep((config.shutdownDelay * 1000).toLong())
                            server.stop(false) // 正常关闭服务器
                        }
                    } catch (e: Exception) {
                        val errorMsg = "异步备份失败: ${e.message}"
                        server.sendMessage(Text.literal("§c$errorMsg"))
                    }
                }

                // 立即返回，表示备份已经开始
                return "备份任务已启动，正在后台处理..."
            }
        } catch (e: Exception) {
            val errorMsg = "备份失败: ${e.message}"
            server.sendMessage(Text.literal("§c$errorMsg"))
            errorMsg
        }
    }

    private fun createCompressedBackupAsync(manual: Boolean): String {
        val backupName = generateBackupName(manual)
        val backupFile = backupDir.resolve("$backupName.zip").toFile()

        // 定义三个世界文件夹 - 使用明确的类型声明
        val worldFolders: List<Pair<String, Path>> = listOf(
            Pair("world", server.runDirectory.resolve("world")),
            Pair("world_nether", server.runDirectory.resolve("world_nether")),
            Pair("world_the_end", server.runDirectory.resolve("world_the_end"))
        )

        // 检查是否有至少一个世界文件夹存在
        val existingFolders = worldFolders.filter { (_, folder) -> Files.exists(folder) }

        if (existingFolders.isEmpty()) {
            return "找不到任何世界文件夹"
        }

        // 创建ZIP压缩文件
        FileOutputStream(backupFile).use { fileOut ->
            ZipOutputStream(fileOut).use { zipOut ->
                existingFolders.forEach { pair ->
                    val name = pair.first
                    val folder = pair.second.toFile()
                    val startTime = System.currentTimeMillis()
                    server.sendMessage(Text.literal("§7正在压缩 $name..."))

                    // 压缩文件夹
                    compressFolderToZip(folder, name, zipOut)

                    val elapsed = System.currentTimeMillis() - startTime
                    server.sendMessage(Text.literal("§a$name 压缩完成 (${elapsed}ms)"))
                }
            }
        }

        val fileSize = backupFile.length() / (1024 * 1024) // MB
        return "备份创建成功: $backupName.zip (${fileSize}MB) 路径: ${backupFile.absolutePath}"
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
                // 对于目录，添加一个目录条目（有些ZIP工具需要这个）
                zipOut.putNextEntry(ZipEntry("$entryName/"))
                zipOut.closeEntry()

                // 递归压缩子目录
                compressFolderToZip(file, entryName, zipOut)
            } else {
                // 对于文件，创建ZIP条目并写入数据
                try {
                    zipOut.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { fileIn ->
                        fileIn.copyTo(zipOut, 8192) // 使用8KB缓冲区
                    }
                    zipOut.closeEntry()
                } catch (_: Exception) {
                    // 如果文件被锁定，跳过并记录
                    server.sendMessage(Text.literal("§7跳过被锁定的文件: ${file.absolutePath}"))
                }
            }
        }
    }

    private fun cleanupOldBackups() {
        try {
            val backups = backupDir.toFile().listFiles { file ->
                file.isFile && file.name.endsWith(".zip")
            } ?: return

            if (backups.size > config.maxBackups) {
                backups.sortedBy { it.lastModified() }
                    .take(backups.size - config.maxBackups)
                    .forEach {
                        it.delete()
                        server.sendMessage(Text.literal("§7清理旧备份: ${it.name}"))
                    }
            }
        } catch (e: Exception) {
            server.sendMessage(Text.literal("§c清理备份时出错: ${e.message}"))
        }
    }

    private fun startScheduledBackup() {
        if (backupScheduler != null) {
            backupScheduler?.shutdown()
        }

        if (config.backupEnabled) {
            backupScheduler = Executors.newSingleThreadScheduledExecutor()
            backupScheduler?.scheduleAtFixedRate(
                { createBackup() },
                config.backupInterval.toLong(),
                config.backupInterval.toLong(),
                TimeUnit.MINUTES
            )
        }
    }

    private fun stopScheduledBackup() {
        backupScheduler?.shutdown()
        backupScheduler = null
    }

    fun toggleBackup(enabled: Boolean): String {
        config.backupEnabled = enabled
        saveConfig()

        if (enabled) {
            startScheduledBackup()
            return "§a自动备份已启用"
        } else {
            stopScheduledBackup()
            return "§c自动备份已禁用"
        }
    }

    fun setBackupPath(path: String): String {
        config.backupPath = path
        saveConfig()

        // 重新初始化备份目录
        backupDir = resolveBackupPath(path)
        Files.createDirectories(backupDir)

        return "§a备份路径已设置为: ${backupDir.toAbsolutePath()}"
    }

    fun setBackupNameFormat(format: String): String {
        config.backupNameFormat = format
        saveConfig()
        return "§a备份名称格式已设置为: $format"
    }

    fun setNotifyPlayers(notify: Boolean): String {
        config.notifyPlayers = notify
        saveConfig()
        return if (notify) "§a备份时将会通知玩家" else "§c备份时将不会通知玩家"
    }

    fun setBackupInterval(minutes: Int): String {
        config.backupInterval = minutes.coerceAtLeast(1)
        saveConfig()
        startScheduledBackup()
        return "§a备份间隔已设置为 ${config.backupInterval} 分钟"
    }

    fun setNoticeTime(seconds: Int): String {
        config.noticeTime = seconds.coerceIn(0, 300) // 最多5分钟
        saveConfig()
        return "§a备份前提示时间已设置为 ${config.noticeTime} 秒"
    }

    fun setShutdownDelay(seconds: Int): String {
        config.shutdownDelay = seconds.coerceIn(1, 60) // 1-60秒
        saveConfig()
        return "§a备份完成后关闭服务器延迟已设置为 ${config.shutdownDelay} 秒"
    }

    fun listBackups(): List<String> {
        return backupDir.toFile().listFiles { file ->
            file.isFile && file.name.endsWith(".zip")
        }?.map {
            val size = it.length() / (1024 * 1024) // MB
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(it.lastModified()))
            "${it.name.substringBeforeLast(".zip")} (${size}MB, $date)"
        }?.sortedDescending() ?: emptyList()
    }
}