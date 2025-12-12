package com.quesox.bar

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object BackupAlwaysRight : ModInitializer {
	private val logger = LoggerFactory.getLogger("backupalwaysright")

	override fun onInitialize() {
		logger.info("初始化 BackupAlwaysRight 模组")
		logger.info("Initializing BackupAlwaysRight mod")
		// 初始化备份管理器
		BackupManager.initialize()

		// 注册指令
		BackupCommands.register()

		logger.info("BackupAlwaysRight 模组已加载")



		logger.info("BackupAlwaysRight mod loaded")
	}
}