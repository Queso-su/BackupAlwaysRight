package com.quesox.bar

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

object BackupCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<ServerCommandSource>) {
        // 创建主命令 bar
        val backupCommand = CommandManager.literal("bar")

        // 所有子命令
        backupCommand
            .then(CommandManager.literal("start")
                .executes { executeBackupNow(it, false) })
            .then(CommandManager.literal("list")
                .executes { listBackups(it) })
            // 修改后的interval命令，支持minute/hour/day子命令
            .then(CommandManager.literal("interval")
                .then(CommandManager.literal("minute")
                    .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                        .executes { setBackupIntervalMinutes(it) }))
                .then(CommandManager.literal("hour")
                    .then(CommandManager.argument("hours", IntegerArgumentType.integer(1, 24))
                        .executes { setBackupIntervalHours(it) }))
                .then(CommandManager.literal("day")
                    .then(CommandManager.argument("days", IntegerArgumentType.integer(1, 30))
                        .executes { setBackupIntervalDays(it) })))
            .then(CommandManager.literal("autobackup")
                .then(CommandManager.argument("state", StringArgumentType.string())
                    .suggests { _, builder ->
                        builder.suggest("enable").suggest("disable").buildFuture()
                    }
                    .executes { toggleBackup(it) }))

            .then(CommandManager.literal("shutdown")
                .executes { executeBackupNow(it, true) }
                .then(CommandManager.literal("delay")
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 60))
                        .executes { setShutdownDelay(it) })))
            // 新增的命令
            .then(CommandManager.literal("debug")
                .then(CommandManager.argument("state", StringArgumentType.string())
                    .suggests { _, builder ->
                        builder.suggest("enable").suggest("disable").buildFuture()
                    }
                    .executes { setDebugMode(it) }))

            .then(CommandManager.literal("status")
                .executes { showStatus(it) })
            .then(CommandManager.literal("reload")
                .executes { reloadConfig(it) })

        // 注册主命令
        dispatcher.register(backupCommand)
    }

    private fun executeBackupNow(context: CommandContext<ServerCommandSource>, shutdown: Boolean): Int {
        val source = context.source
        val shutdownText = if (shutdown) "并关闭服务器" else ""

        if (shutdown) {
            source.sendFeedback({
                LanguageManager.tr("backupalwaysright.warning_shutdown")
            }, true)
        }

        source.sendFeedback({
            LanguageManager.tr("backupalwaysright.backup_starting", shutdownText)
        }, true)

        val result = BackupManager.createBackup(true, shutdown)
        source.sendFeedback({
            Text.literal(result)
        }, true)

        return 1
    }

    private fun listBackups(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val backups = BackupManager.listBackups()

        if (backups.isEmpty()) {
            source.sendFeedback({
                LanguageManager.tr("backupalwaysright.no_backups_found")
            }, false)
        } else {
            source.sendFeedback({
                LanguageManager.tr("backupalwaysright.backup_list_title")
            }, false)

            backups.forEachIndexed { index, backup ->
                source.sendFeedback({
                    LanguageManager.tr("backupalwaysright.backup_item", index + 1, backup)
                }, false)
            }
        }

        return 1
    }

    // 设置分钟间隔
    private fun setBackupIntervalMinutes(context: CommandContext<ServerCommandSource>): Int {
        val minutes = IntegerArgumentType.getInteger(context, "minutes")
        val result = BackupManager.setBackupIntervalMinutes(minutes)
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    // 设置小时间隔
    private fun setBackupIntervalHours(context: CommandContext<ServerCommandSource>): Int {
        val hours = IntegerArgumentType.getInteger(context, "hours")
        val result = BackupManager.setBackupIntervalHours(hours)
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    // 设置天间隔
    private fun setBackupIntervalDays(context: CommandContext<ServerCommandSource>): Int {
        val days = IntegerArgumentType.getInteger(context, "days")
        val result = BackupManager.setBackupIntervalDays(days)
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    private fun toggleBackup(context: CommandContext<ServerCommandSource>): Int {
        val state = StringArgumentType.getString(context, "state")
        val result = BackupManager.toggleBackup(state.equals("enable", true))
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    private fun setShutdownDelay(context: CommandContext<ServerCommandSource>): Int {
        val seconds = IntegerArgumentType.getInteger(context, "seconds")
        val result = BackupManager.setShutdownDelay(seconds)
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    private fun setDebugMode(context: CommandContext<ServerCommandSource>): Int {
        val state = StringArgumentType.getString(context, "state")
        val result = BackupManager.setDebugMode(state.equals("enable", true))
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }

    private fun showStatus(context: CommandContext<ServerCommandSource>): Int {
        val status = BackupManager.getBackupStatus()
        context.source.sendFeedback({
            Text.literal(status)
        }, false)
        return 1
    }

    // 重新加载配置
    private fun reloadConfig(context: CommandContext<ServerCommandSource>): Int {
        val result = BackupManager.reloadConfig()
        context.source.sendFeedback({
            Text.literal(result)
        }, true)
        return 1
    }
}