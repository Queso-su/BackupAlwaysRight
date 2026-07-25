package com.quesox.bar

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object BackupCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // 创建主命令 bar
        val backupCommand = Commands.literal("bar")

        // 所有子命令
        backupCommand
            .then(Commands.literal("start")
                .requires(Commands.hasPermission(Commands.LEVEL_MODERATORS))
                .executes { executeBackupNow(it, false) })
            .then(Commands.literal("list")
                .executes { listBackups(it) })
            // 修改后的interval命令，支持minute/hour/day子命令
            .then(Commands.literal("interval")
                .then(Commands.literal("minute")
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { setBackupIntervalMinutes(it) }))
                .then(Commands.literal("hour")
                    .then(Commands.argument("hours", IntegerArgumentType.integer(1, 24))
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { setBackupIntervalHours(it) }))
                .then(Commands.literal("day")
                    .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { setBackupIntervalDays(it) })))
            .then(Commands.literal("autobackup")
                .then(Commands.argument("state", StringArgumentType.string())
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .suggests { _, builder ->
                        builder.suggest("enable").suggest("disable").buildFuture()
                    }
                    .executes { toggleBackup(it) }))

            .then(Commands.literal("shutdown")
                .executes { executeBackupNow(it, true) }
                .then(Commands.literal("delay")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                        .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                        .executes { setShutdownDelay(it) })))
            // 新增的命令
            .then(Commands.literal("debug")
                .then(Commands.argument("state", StringArgumentType.string())
                    .suggests { _, builder ->
                        builder.suggest("enable").suggest("disable").buildFuture()
                    }
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { setDebugMode(it) }))

            .then(Commands.literal("status")
                .executes { showStatus(it) })
            .then(Commands.literal("reload")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes { reloadConfig(it) })

        // 注册主命令
        dispatcher.register(backupCommand)
    }

    private fun executeBackupNow(context: CommandContext<CommandSourceStack>, shutdown: Boolean): Int {
        val source = context.source
        val shutdownText = if (shutdown) "并关闭服务器/AND ShutDown Server" else ""

        if (shutdown) {
            source.sendSuccess({
                LanguageManager.tr("backupalwaysright.warning_shutdown")
            }, true)
        }

        source.sendSuccess({
            LanguageManager.tr("backupalwaysright.backup_starting", shutdownText)
        }, true)

        val result = BackupManager.createBackup(true, shutdown)
        source.sendSuccess({
            Component.literal(result)
        }, true)

        return 1
    }

    private fun listBackups(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val backups = BackupManager.listBackups()

        if (backups.isEmpty()) {
            source.sendSuccess({
                LanguageManager.tr("backupalwaysright.no_backups_found")
            }, false)
        } else {
            source.sendSuccess({
                LanguageManager.tr("backupalwaysright.backup_list_title")
            }, false)

            backups.forEachIndexed { index, backup ->
                source.sendSuccess({
                    LanguageManager.tr("backupalwaysright.backup_item", index + 1, backup)
                }, false)
            }
        }

        return 1
    }

    // 设置分钟间隔
    private fun setBackupIntervalMinutes(context: CommandContext<CommandSourceStack>): Int {
        val minutes = IntegerArgumentType.getInteger(context, "minutes")
        val result = BackupManager.setBackupIntervalMinutes(minutes)
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    // 设置小时间隔
    private fun setBackupIntervalHours(context: CommandContext<CommandSourceStack>): Int {
        val hours = IntegerArgumentType.getInteger(context, "hours")
        val result = BackupManager.setBackupIntervalHours(hours)
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    // 设置天间隔
    private fun setBackupIntervalDays(context: CommandContext<CommandSourceStack>): Int {
        val days = IntegerArgumentType.getInteger(context, "days")
        val result = BackupManager.setBackupIntervalDays(days)
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    private fun toggleBackup(context: CommandContext<CommandSourceStack>): Int {
        val state = StringArgumentType.getString(context, "state")
        val result = BackupManager.toggleBackup(state.equals("enable", true))
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    private fun setShutdownDelay(context: CommandContext<CommandSourceStack>): Int {
        val seconds = IntegerArgumentType.getInteger(context, "seconds")
        val result = BackupManager.setShutdownDelay(seconds)
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    private fun setDebugMode(context: CommandContext<CommandSourceStack>): Int {
        val state = StringArgumentType.getString(context, "state")
        val result = BackupManager.setDebugMode(state.equals("enable", true))
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }

    private fun showStatus(context: CommandContext<CommandSourceStack>): Int {
        val status = BackupManager.getBackupStatus()
        context.source.sendSuccess({
            Component.literal(status)
        }, false)
        return 1
    }

    // 重新加载配置
    private fun reloadConfig(context: CommandContext<CommandSourceStack>): Int {
        val result = BackupManager.reloadConfig()
        context.source.sendSuccess({
            Component.literal(result)
        }, true)
        return 1
    }
}