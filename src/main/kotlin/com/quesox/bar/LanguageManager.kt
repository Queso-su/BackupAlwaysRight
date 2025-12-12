package com.quesox.bar

import net.minecraft.text.MutableText
import net.minecraft.text.Text
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import java.io.File

object LanguageManager {
    // 支持的语种
    enum class Language(val code: String) {
        AUTO("auto"),
        EN_US("en_us"),
        ZH_CN("zh_cn");

        companion object {
            fun fromCode(code: String): Language {
                return values().find { it.code.equals(code, ignoreCase = true) } ?: AUTO
            }
        }
    }

    private var currentLanguage = Language.AUTO
    private val translations = mutableMapOf<String, String>()
    private var fallbackTranslations = mutableMapOf<String, String>()
    private var serverRunDirectory: Path? = null

    fun initialize(serverRunDirectory: Path) {
        this.serverRunDirectory = serverRunDirectory

        // 从配置文件读取语言设置
        loadLanguageSetting(serverRunDirectory)

        // 加载翻译文件
        loadTranslations()

        // 如果配置为auto，根据系统语言自动选择
        if (currentLanguage == Language.AUTO) {
            val systemLang = Locale.getDefault().language
            currentLanguage = if (systemLang.startsWith("zh")) Language.ZH_CN else Language.EN_US
        }

        // 重新加载所选语言的翻译
        loadTranslations()
    }

    private fun loadLanguageSetting(serverRunDirectory: Path) {
        val configFile = serverRunDirectory.resolve("config/bar.conf")
        if (Files.exists(configFile)) {
            try {
                Files.readAllLines(configFile).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.startsWith("language=")) {
                        val langCode = trimmed.substringAfter("language=").trim()
                        currentLanguage = Language.fromCode(langCode)
                    }
                }
            } catch (e: Exception) {
                // 使用默认语言
                currentLanguage = Language.AUTO
            }
        }
    }

    private fun loadTranslations() {
        translations.clear()

        // 首先加载回退语言（英文）
        loadTranslationFile(Language.EN_US)
        fallbackTranslations = HashMap(translations)

        // 如果当前语言不是英文，加载当前语言的翻译
        if (currentLanguage != Language.EN_US) {
            translations.clear()
            loadTranslationFile(currentLanguage)
        }
    }

    private fun loadTranslationFile(language: Language) {
        try {
            val resourcePath = "/assets/backupalwaysright/lang/${language.code}.json"
            var inputStream: InputStream? = null

            // 首先尝试从类路径加载（JAR内部）
            inputStream = javaClass.getResourceAsStream(resourcePath)

            if (inputStream == null) {
                // 如果从类路径加载失败，尝试从文件系统加载（开发环境）
            //    println("[LanguageManager] Resource not found in classpath: $resourcePath")
                if (serverRunDirectory != null) {
                    val externalFile = serverRunDirectory!!.resolve("config/lang/${language.code}.json")
                    if (Files.exists(externalFile)) {
                  //      println("[LanguageManager] Loading from external file: $externalFile")
                        inputStream = Files.newInputStream(externalFile)
                    }
                }
            } else {
               // println("[LanguageManager] Loaded resource from classpath: $resourcePath")
            }

            if (inputStream == null) {
              //  println("[LanguageManager] Using hardcoded translations for ${language.code}")
                loadHardcodedTranslations(language)
                return
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JsonParser.parseString(jsonString).asJsonObject

            jsonObject.entrySet().forEach { entry ->
                translations[entry.key] = entry.value.asString
            }
            inputStream.close()

          //  println("[LanguageManager] Loaded ${translations.size} translations for ${language.code}")
        } catch (e: Exception) {
          //  println("[LanguageManager] Error loading translations for ${language.code}: ${e.message}")
            loadHardcodedTranslations(language)
        }
    }
    private fun loadHardcodedTranslations(language: Language) {
        translations.clear()
        when (language) {
            Language.EN_US -> {
                translations.putAll(mapOf(
                    "backupalwaysright.mod_initialized" to "Backup Always Right mod loaded",
                    "backupalwaysright.system_started" to "Backup system started, interval: %1\$s minutes",
                    "backupalwaysright.backup_path" to "Backup path: %1\$s",
                    "backupalwaysright.backup_world_start" to "Starting world data backup...",
                    "backupalwaysright.backup_task_started" to "Backup task started, processing in background...",
                    "backupalwaysright.verify_backup" to "Backup completed, verifying backup integrity...",
                    "backupalwaysright.verify_success" to "Backup verification passed",
                    "backupalwaysright.backup_path_result" to "Path %1\$s: %2\$s",
                    "backupalwaysright.backup_created" to "Backup created successfully: %1\$s.zip (%2\$sMB) Path: %3\$s"
                ))
            }
            Language.ZH_CN -> {
                translations.putAll(mapOf(
                    "backupalwaysright.mod_initialized" to "备对模组已加载",
                    "backupalwaysright.system_started" to "备份系统已启动，间隔: %1\$s分钟",
                    "backupalwaysright.backup_path" to "备份路径: %1\$s",
                    "backupalwaysright.backup_world_start" to "开始备份世界数据...",
                    "backupalwaysright.backup_task_started" to "备份任务已启动，正在后台处理...",
                    "backupalwaysright.verify_backup" to "备份完成，验证备份完整性...",
                    "backupalwaysright.verify_success" to "备份验证通过",
                    "backupalwaysright.backup_path_result" to "路径 %1\$s: %2\$s",
                    "backupalwaysright.backup_created" to "备份创建成功: %1\$s.zip (%2\$sMB) 路径: %3\$s"
                ))
            }
            else -> {
                // 默认使用英文
                loadHardcodedTranslations(Language.EN_US)
            }
        }

        // 确保回退翻译总是英文
        if (language != Language.EN_US) {
            val enTranslations = mutableMapOf<String, String>()
            loadHardcodedTranslations(Language.EN_US)
            enTranslations.putAll(translations)
            fallbackTranslations = enTranslations
        } else {
            fallbackTranslations = HashMap(translations)
        }
    }

    fun setLanguage(language: Language, serverRunDirectory: Path): Boolean {
        currentLanguage = language
        this.serverRunDirectory = serverRunDirectory

        // 重新加载翻译
        loadTranslations()

        // 更新配置文件
        return saveLanguageSetting(serverRunDirectory)
    }

    private fun saveLanguageSetting(serverRunDirectory: Path): Boolean {
        val configFile = serverRunDirectory.resolve("config/bar.conf")
        try {
            if (Files.exists(configFile)) {
                val lines = Files.readAllLines(configFile).toMutableList()
                var languageFound = false

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.isNotEmpty() && !line.startsWith("#") && line.startsWith("language=")) {
                        lines[i] = "language=${currentLanguage.code}"
                        languageFound = true
                        break
                    }
                }

                if (!languageFound) {
                    // 如果配置文件中没有语言设置，在文件末尾添加
                    lines.add("# 语言设置 (auto, en_us, zh_cn)")
                    lines.add("language=${currentLanguage.code}")
                }

                Files.write(configFile, lines)
            } else {
                // 如果配置文件不存在，创建默认配置
                val configDir = serverRunDirectory.resolve("config")
                Files.createDirectories(configDir)
                val defaultConfig = """
                    # 语言设置 (auto, en_us, zh_cn)
                    language=${currentLanguage.code}
                    
                    # 其他配置...
                """.trimIndent()
                Files.write(configFile, defaultConfig.toByteArray())
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun getCurrentLanguage(): Language {
        return currentLanguage
    }

    // 获取翻译文本（支持占位符）
    fun translate(key: String, vararg args: Any): MutableText {
        var translation = translations[key] ?: fallbackTranslations[key] ?: key

        // 替换占位符
        if (args.isNotEmpty()) {
            try {
                for (i in args.indices) {
                    translation = translation.replace("%${i + 1}\$s", args[i].toString())
                    translation = translation.replace("%${i + 1}\$d", args[i].toString())
                }
            } catch (e: Exception) {
                // 如果替换失败，返回原始翻译
            }
        }

        return Text.literal(translation)
    }

    // 简化的翻译方法
    fun tr(key: String, vararg args: Any): MutableText {
        return translate(key, *args)
    }

    // 检查翻译键是否存在
    fun hasTranslation(key: String): Boolean {
        return translations.containsKey(key) || fallbackTranslations.containsKey(key)
    }
}