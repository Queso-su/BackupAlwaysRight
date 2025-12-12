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
            .then(CommandManager.literal("interval")
                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                    .executes { setBackupInterval(it) }))
            .then(CommandManager.literal("autobackup")
                .then(CommandManager.argument("state", StringArgumentType.string())
                    .suggests { _, builder ->
                        builder.suggest("enable").suggest("disable").buildFuture()
                    }
                    .executes { toggleBackup(it) }))
            .then(CommandManager.literal("noticetime")
                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 300))
                    .executes { setNoticeTime(it) }))
            .then(CommandManager.literal("shutdown")
                .executes { executeBackupNow(it, true) }
                .then(CommandManager.literal("delay")
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 60))
                        .executes { setShutdownDelay(it) })))

        // 注册主命令
        dispatcher.register(backupCommand)
    }

    private fun executeBackupNow(context: CommandContext<ServerCommandSource>, shutdown: Boolean): Int {
        val source = context.source
        val shutdownText = if (shutdown) "并关闭服务器" else ""

        if (shutdown) {
            source.sendFeedback({
                Text.literal("§c警告: 服务器将在备份完成后关闭!")
            }, true)
        }

        source.sendFeedback({
            Text.literal("§7正在创建备份$shutdownText...")
        }, true)

        val result = BackupManager.createBackup(true, shutdown)
        source.sendFeedback({
            Text.literal("§a$result")
        }, true)

        return 1
    }

    private fun listBackups(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val backups = BackupManager.listBackups()

        if (backups.isEmpty()) {
            source.sendFeedback({
                Text.literal("§7没有找到备份")
            }, false)
        } else {
            source.sendFeedback({
                Text.literal("§6=== 备份列表 (.zip格式) ===")
            }, false)

            backups.forEachIndexed { index, backup ->
                source.sendFeedback({
                    Text.literal("§7${index + 1}. §f$backup")
                }, false)
            }

            source.sendFeedback({
                Text.literal("§6备份文件保存在服务器目录的 backups/ 文件夹中")
                Text.literal("§6文件包含三个世界: world, world_nether, world_the_end")
            }, false)
        }

        return 1
    }

    private fun setBackupInterval(context: CommandContext<ServerCommandSource>): Int {
        val minutes = IntegerArgumentType.getInteger(context, "minutes")
        val result = BackupManager.setBackupInterval(minutes)
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

    private fun setNoticeTime(context: CommandContext<ServerCommandSource>): Int {
        val seconds = IntegerArgumentType.getInteger(context, "seconds")
        val result = BackupManager.setNoticeTime(seconds)
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
}